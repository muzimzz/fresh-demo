package com.example.freshdemo.common.auth.jwt;

import com.example.freshdemo.admin.domain.repository.AdminRepository;
import com.example.freshdemo.member.domain.repository.MemberRepository;
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
 * Refresh Token 저장소. key = "refreshToken:{role}:{id}". Redis를 1차 저장소로, DB 백업은
 * Member/Admin 행 자체의 refreshTokenHash/refreshTokenExpiresAt에 write-through로 남긴다.
 *
 * [LG-fm 컨벤션 리팩토링] common.auth.jwt로 이동. 이 클래스는 common(공용 인프라) 소속이면서
 * member/admin 두 도메인의 domain.repository를 직접 의존한다 — "인증 인프라는 common.auth에
 * 둔다"는 이번 리팩토링의 방침을 따르기 위한 선택이고, 도메인 경계 원칙과는 긴장이 있다는 점을
 * DESIGN_NOTES.md에 기록해 둔다(다음 라운드에 포트-어댑터로 역전시킬지, auth를 독립 도메인으로
 * 승격할지 검토 필요).
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class RefreshTokenRepository {

    private static final String KEY_PREFIX = "refreshToken:";

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
