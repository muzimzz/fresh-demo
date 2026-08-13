package com.example.freshdemo.auth.jwt;

import com.example.freshdemo.admin.repository.AdminRepository;
import com.example.freshdemo.member.repository.MemberRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Refresh Token 저장소. key = "refreshToken:{role}:{id}".
 *
 * Redis를 1차 저장소(빠른 조회/삭제)로 쓰고, DB 백업은 별도 테이블이 아니라 목표 DDL을 따라
 * 소유 엔티티(Member/Admin) 행 자체의 refreshTokenHash/refreshTokenExpiresAt 컬럼에 write-through로
 * 남긴다 — Redis가 죽어도 재발급이 전부 막혀서 전체 유저가 강제 로그아웃되는 걸 막기 위한 백업 계층.
 *
 * 예전엔 role+ownerId로 찾는 별도 RefreshTokenBackup 테이블을 썼는데, DDL을 받아들이면서 그 자리를
 * Member/Admin 테이블 자체로 옮겼다 — 그래서 이 클래스는 이제 (role 문자열, id)만으로는 어느 테이블에
 * 백업을 써야 할지 알 수 없고, 호출부가 TokenType(MEMBER/ADMIN)을 같이 넘겨줘야 한다. 목표 DDL의
 * admin 테이블엔 이 두 컬럼이 없지만(회원 세션만 고려한 설계로 보임), 관리자도 이 저장소를 그대로
 * 공유해서 쓰므로 Admin에도 대칭으로 컬럼을 추가했다(Admin.refreshTokenHash 주석 참고).
 *
 * 폴백 기준: Redis 호출이 "정상적으로 값이 없다"(Optional.empty)가 아니라 "연결 자체가 안 된다"
 * (DataAccessException 계열)일 때만 DB로 넘어간다 — 그냥 키가 없는 것까지 DB를 보러 가면 이미
 * 로그아웃/삭제된 세션을 잘못 살려낼 수 있다.
 *
 * role까지 키에 넣은 건 단순 구분자 이상의 효과도 있다 — 예를 들어 관리자 권한이 ADMIN→SUPER_ADMIN으로
 * 바뀌면 예전 role로 저장돼 있던 refreshToken은 키 자체가 달라져서 자연히 조회 불가(=사실상 무효화) 된다.
 *
 * Redis/DB에 저장하는 값은 refreshToken 원문이 아니라 SHA-256 해시(TokenHasher)다 — 저장소가
 * 유출돼도 그 값을 그대로 제시해서 로그인할 수는 없다. 서버는 원문을 다시 복원할 필요가 없으니(
 * 클라이언트 쿠키에 들어있는 원문과 "일치하는지"만 확인하면 됨) 암호화가 아니라 해싱을 썼다 —
 * 비밀번호와 같은 이유.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class RefreshTokenRepository {

    private static final String KEY_PREFIX = "refreshToken:";

    // DefaultRedisScript는 (String script) / (String script, Class<T>) 생성자만 있고 Resource를
    // 바로 받는 생성자는 없다 — 그래서 no-arg 생성자 + setLocation(Resource)로 클래스패스의
    // .lua 파일을 읽어들인다(내부적으로 ResourceScriptSource가 스크립트 텍스트로 변환해줌).
    private static final RedisScript<Long> COMPARE_AND_SAVE_SCRIPT = loadCompareAndSaveScript();

    private static RedisScript<Long> loadCompareAndSaveScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/refresh_token_cas.lua"));
        script.setResultType(Long.class);
        return script;
    }

    private final StringRedisTemplate redisTemplate;
    private final MemberRepository memberRepository;
    private final AdminRepository adminRepository;

    @Transactional
    public void save(TokenType type, String role, Long id, String refreshToken, Duration ttl) {
        String tokenHash = TokenHasher.sha256(refreshToken);
        trySaveBackup(type, id, tokenHash, LocalDateTime.now().plus(ttl));

        try {
            redisTemplate.opsForValue().set(key(role, id), tokenHash, ttl);
        } catch (DataAccessException e) {
            log.warn("event=REDIS_SAVE_FAILED role={} id={} — DB 백업만 반영됨", role, id, e);
        }
    }

    @Transactional
    public void delete(TokenType type, String role, Long id) {
        try {
            clearBackup(type, id);
        } catch (DataAccessException e) {
            log.warn("event=DB_BACKUP_DELETE_FAILED role={} id={} — DB 백업 삭제 실패(로그아웃/삭제 자체는 계속 진행)", role, id, e);
        }
        try {
            redisTemplate.delete(key(role, id));
        } catch (DataAccessException e) {
            log.warn("event=REDIS_DELETE_FAILED role={} id={} — DB 백업만 반영됨", role, id, e);
        }
    }

    /**
     * "현재 저장된 값이 oldRefreshToken과 같을 때만 newRefreshToken으로 교체"를 원자적으로 수행한다.
     * Redis가 살아있으면 Lua 스크립트로 처리하고(조회+비교+저장이 Redis 안에서 한 번에 원자적으로
     * 끝남), Redis 자체가 죽어있으면 소유 엔티티(Member/Admin)에 대한 조건부 UPDATE(영향받은 row
     * 수로 성공 여부 판단)로 같은 보장을 흉내낸다.
     *
     * @return 교체에 성공했으면 true. false면 이미 다른 값으로 바뀐 상태 — 정상적인 동시 요청 race일
     *         수도, 이미 폐기된 옛 토큰의 재사용(탈취 의심)일 수도 있다. 호출부(AuthController)가
     *         구분 없이 재사용으로 간주해 세션을 무효화한다.
     */
    @Transactional
    public boolean compareAndSave(TokenType type, String role, Long id, String oldRefreshToken, String newRefreshToken, Duration ttl) {
        LocalDateTime expiresAt = LocalDateTime.now().plus(ttl);
        String oldHash = TokenHasher.sha256(oldRefreshToken);
        String newHash = TokenHasher.sha256(newRefreshToken);

        try {
            Long result = redisTemplate.execute(
                    COMPARE_AND_SAVE_SCRIPT,
                    List.of(key(role, id)),
                    oldHash, newHash, String.valueOf(ttl.toMillis())
            );
            boolean rotated = result != null && result == 1L;
            if (rotated) {
                trySaveBackup(type, id, newHash, expiresAt);
            }
            return rotated;
        } catch (DataAccessException e) {
            log.warn("event=REDIS_CAS_FAILED role={} id={} — DB CAS로 폴백", role, id, e);
            int updated = compareAndSetBackup(type, id, oldHash, newHash, expiresAt);
            return updated > 0;
        }
    }

    /**
     * DB 백업은 어디까지나 Redis 장애에 대비한 보조 수단이다 — Redis(1차 저장소) 쓰기가 이미
     * 성공했거나 곧 성공할 예정이라면, DB 백업 쓰기 자체가 실패해도(DB 다운, 커넥션 풀 고갈 등)
     * 로그인/재발급 같은 상위 요청 전체를 실패시키거나 @Transactional을 롤백시키면 안 된다 —
     * 그러면 "DB는 그냥 보조 수단"이라는 설계 의도와 반대로, DB 장애가 오히려 Redis가 멀쩡한데도
     * 전체 서비스를 막아버리는 단일 장애점이 된다. 그래서 여기서 예외를 잡아 로그만 남기고 삼킨다
     * — 대가로 "DB 백업이 살짝 stale하거나 이번 회차는 아예 안 남을 수 있다"는 리스크를 감수한다
     * (Redis 자체가 죽어있는 진짜 장애 상황과 동시에 DB까지 죽는 이중 장애가 아닌 이상 문제되지 않음).
     */
    private void trySaveBackup(TokenType type, Long id, String tokenHash, LocalDateTime expiresAt) {
        try {
            int updated = updateBackup(type, id, tokenHash, expiresAt);
            if (updated == 0) {
                log.warn("event=DB_BACKUP_SAVE_SKIPPED type={} id={} — 대상 행을 찾지 못함", type, id);
            }
        } catch (DataAccessException e) {
            log.warn("event=DB_BACKUP_SAVE_FAILED type={} id={} — Redis만 반영됨(DB 백업 유실 가능, 다음 쓰기 때 다시 시도됨)",
                    type, id, e);
        }
    }

    private int updateBackup(TokenType type, Long id, String tokenHash, LocalDateTime expiresAt) {
        return switch (type) {
            case MEMBER -> memberRepository.updateRefreshToken(id, tokenHash, expiresAt);
            case ADMIN -> adminRepository.updateRefreshToken(id, tokenHash, expiresAt);
        };
    }

    private void clearBackup(TokenType type, Long id) {
        switch (type) {
            case MEMBER -> memberRepository.clearRefreshToken(id);
            case ADMIN -> adminRepository.clearRefreshToken(id);
        }
    }

    private int compareAndSetBackup(TokenType type, Long id, String oldHash, String newHash, LocalDateTime expiresAt) {
        return switch (type) {
            case MEMBER -> memberRepository.compareAndSetRefreshToken(id, oldHash, newHash, expiresAt);
            case ADMIN -> adminRepository.compareAndSetRefreshToken(id, oldHash, newHash, expiresAt);
        };
    }

    private String key(String role, Long id) {
        return KEY_PREFIX + role + ":" + id;
    }
}
