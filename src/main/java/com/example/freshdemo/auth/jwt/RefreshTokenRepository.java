package com.example.freshdemo.auth.jwt;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class RefreshTokenRepository {

    private static final String KEY_PREFIX = "refreshToken:";

    private static final RedisScript<Long> COMPARE_AND_SAVE_SCRIPT = new DefaultRedisScript<>(
            new ClassPathResource("scripts/refresh_token_cas.lua"), Long.class);

    private final StringRedisTemplate redisTemplate;
    private final RefreshTokenBackupRepository backupRepository;

    @Transactional
    public void save(String role, UUID id, String refreshToken, Duration ttl) {
        saveBackup(role, id, refreshToken, LocalDateTime.now().plus(ttl));

        try {
            redisTemplate.opsForValue().set(key(role, id), refreshToken, ttl);
        } catch (DataAccessException e) {
            log.warn("event=REDIS_SAVE_FAILED role={} id={} — DB 백업만 반영됨", role, id, e);
        }
    }

    public Optional<String> find(String role, UUID id) {
        try {
            return Optional.ofNullable(redisTemplate.opsForValue().get(key(role, id)));
        } catch (DataAccessException e) {
            log.warn("event=REDIS_FIND_FAILED role={} id={} — DB 백업으로 폴백", role, id, e);
            return findFromBackup(role, id);
        }
    }

    @Transactional
    public void delete(String role, UUID id) {
        backupRepository.deleteByRoleAndOwnerId(role, id);
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
    public boolean compareAndSave(String role, UUID id, String oldRefreshToken, String newRefreshToken, Duration ttl) {
        LocalDateTime expiresAt = LocalDateTime.now().plus(ttl);

        try {
            Long result = redisTemplate.execute(
                    COMPARE_AND_SAVE_SCRIPT,
                    List.of(key(role, id)),
                    oldRefreshToken, newRefreshToken, String.valueOf(ttl.toMillis())
            );
            boolean rotated = result != null && result == 1L;
            if (rotated) {
                saveBackup(role, id, newRefreshToken, expiresAt);
            }
            return rotated;
        } catch (DataAccessException e) {
            log.warn("event=REDIS_CAS_FAILED role={} id={} — DB CAS로 폴백", role, id, e);
            int updated = backupRepository.compareAndSet(role, id, oldRefreshToken, newRefreshToken, expiresAt);
            return updated > 0;
        }
    }

    private void saveBackup(String role, UUID id, String token, LocalDateTime expiresAt) {
        backupRepository.findByRoleAndOwnerId(role, id)
                .ifPresentOrElse(
                        existing -> existing.rotate(token, expiresAt),
                        () -> backupRepository.save(RefreshTokenBackup.builder()
                                .role(role)
                                .ownerId(id)
                                .token(token)
                                .expiresAt(expiresAt)
                                .build())
                );
    }

    private Optional<String> findFromBackup(String role, UUID id) {
        return backupRepository.findByRoleAndOwnerId(role, id)
                .filter(backup -> !backup.isExpired(LocalDateTime.now()))
                .map(RefreshTokenBackup::getToken);
    }

    private String key(String role, UUID id) {
        return KEY_PREFIX + role + ":" + id;
    }
}
