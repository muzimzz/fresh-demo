package com.example.freshdemo.common.auth.jwt;

import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

/**
 * Refresh Token 저장소. key = "refreshToken:{role}:{id}". 순수 Redis 저장소 — Member/Admin을
 * 전혀 모른다. Redis 장애 시 DataAccessException을 그대로 던지며, DB 백업/폴백은 호출자(도메인
 * 소유의 ~TokenService)의 책임이다.
 *
 * [LG-fm 컨벤션 리팩토링 3차] 순환_의존이_없다 ArchUnit 위반 해소: 예전엔 이 클래스가
 * MemberAuthApi/AdminAuthApi를 거쳐 DB 백업 write-through까지 직접 오케스트레이션했다
 * (common→member/admin 엣지의 원인 중 하나). 이제 그 오케스트레이션(DB 백업 저장/삭제/CAS
 * 폴백)은 member.domain.service.MemberTokenService / admin.domain.service.AdminTokenService로
 * 옮겼고, 이 클래스는 Redis 원자적 CAS(compareAndSave)와 단순 저장/삭제만 담당한다.
 */
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

    public void save(String role, Long id, String refreshToken, Duration ttl) {
        redisTemplate.opsForValue().set(key(role, id), TokenHasher.sha256(refreshToken), ttl);
    }

    public void delete(String role, Long id) {
        redisTemplate.delete(key(role, id));
    }

    /** @return true면 회전 성공(원자적 compare-and-set), false면 저장된 값과 불일치(재사용 의심). */
    public boolean compareAndSave(String role, Long id, String oldRefreshToken, String newRefreshToken, Duration ttl) {
        String oldHash = TokenHasher.sha256(oldRefreshToken);
        String newHash = TokenHasher.sha256(newRefreshToken);

        Long result = redisTemplate.execute(
                COMPARE_AND_SAVE_SCRIPT,
                List.of(key(role, id)),
                oldHash, newHash, String.valueOf(ttl.toMillis())
        );
        return result != null && result == 1L;
    }

    private String key(String role, Long id) {
        return KEY_PREFIX + role + ":" + id;
    }
}
