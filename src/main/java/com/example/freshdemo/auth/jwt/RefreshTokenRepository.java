package com.example.freshdemo.auth.jwt;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
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
 * Redis를 1차 저장소(빠른 조회/삭제)로 쓰고, RefreshTokenBackup(MySQL)에 같은 내용을 write-through로
 * 같이 남긴다 — Redis가 죽어도 재발급이 전부 막혀서 전체 유저가 강제 로그아웃되는 걸 막기 위한 백업 계층.
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
 * 비밀번호와 같은 이유. 그래서 저장된 값을 그대로 돌려주는 find() 같은 메서드는 의미가 없고,
 * matches()처럼 "이 원문의 해시가 저장된 해시와 같은가"를 묻는 형태로만 제공한다.
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
    private final RefreshTokenBackupRepository backupRepository;

    @Transactional
    public void save(String role, Long id, String refreshToken, Duration ttl) {
        String tokenHash = TokenHasher.sha256(refreshToken);
        trySaveBackup(role, id, tokenHash, LocalDateTime.now().plus(ttl));

        try {
            redisTemplate.opsForValue().set(key(role, id), tokenHash, ttl);
        } catch (DataAccessException e) {
            log.warn("event=REDIS_SAVE_FAILED role={} id={} — DB 백업만 반영됨", role, id, e);
        }
    }

    /**
     * 들어온 refreshToken(원문)의 해시가 저장된 해시와 같은지 확인한다. 예전엔 저장된 값을 그대로
     * 돌려주는 find()가 있었는데(호출부가 하나도 없어서 죽은 코드였음), 해싱 방식으로 바꾸면서
     * "저장된 원문을 돌려주는" 형태 자체가 더 이상 성립하지 않아 이 메서드로 대체했다.
     */
    public boolean matches(String role, Long id, String candidateToken) {
        String candidateHash = TokenHasher.sha256(candidateToken);
        try {
            String stored = redisTemplate.opsForValue().get(key(role, id));
            return candidateHash.equals(stored);
        } catch (DataAccessException e) {
            log.warn("event=REDIS_FIND_FAILED role={} id={} — DB 백업으로 폴백", role, id, e);
            return findHashFromBackup(role, id)
                    .map(candidateHash::equals)
                    .orElse(false);
        }
    }

    @Transactional
    public void delete(String role, Long id) {
        try {
            backupRepository.deleteByRoleAndOwnerId(role, id);
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
     * 끝남), Redis 자체가 죽어있으면 DB의 조건부 UPDATE(영향받은 row 수로 성공 여부 판단)로 같은
     * 보장을 흉내낸다.
     *
     * @return 교체에 성공했으면 true. false면 이미 다른 값으로 바뀐 상태 — 정상적인 동시 요청 race일
     *         수도, 이미 폐기된 옛 토큰의 재사용(탈취 의심)일 수도 있다. 호출부(AuthController)가
     *         구분 없이 재사용으로 간주해 세션을 무효화한다.
     */
    @Transactional
    public boolean compareAndSave(String role, Long id, String oldRefreshToken, String newRefreshToken, Duration ttl) {
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
                trySaveBackup(role, id, newHash, expiresAt);
            }
            return rotated;
        } catch (DataAccessException e) {
            log.warn("event=REDIS_CAS_FAILED role={} id={} — DB CAS로 폴백", role, id, e);
            int updated = backupRepository.compareAndSet(role, id, oldHash, newHash, expiresAt);
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
    private void trySaveBackup(String role, Long id, String tokenHash, LocalDateTime expiresAt) {
        try {
            saveBackup(role, id, tokenHash, expiresAt);
        } catch (DataAccessException e) {
            log.warn("event=DB_BACKUP_SAVE_FAILED role={} id={} — Redis만 반영됨(DB 백업 유실 가능, 다음 쓰기 때 다시 시도됨)",
                    role, id, e);
        }
    }

    private void saveBackup(String role, Long id, String tokenHash, LocalDateTime expiresAt) {
        backupRepository.findByRoleAndOwnerId(role, id)
                .ifPresentOrElse(
                        existing -> existing.rotate(tokenHash, expiresAt),
                        () -> backupRepository.save(RefreshTokenBackup.builder()
                                .role(role)
                                .ownerId(id)
                                .tokenHash(tokenHash)
                                .expiresAt(expiresAt)
                                .build())
                );
    }

    private Optional<String> findHashFromBackup(String role, Long id) {
        return backupRepository.findByRoleAndOwnerId(role, id)
                .filter(backup -> !backup.isExpired(LocalDateTime.now()))
                .map(RefreshTokenBackup::getTokenHash);
    }

    private String key(String role, Long id) {
        return KEY_PREFIX + role + ":" + id;
    }
}
