package com.example.freshdemo.common.auth.jwt;

import java.time.Duration;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * "이 시각 이전에 발급된 accessToken은 전부 무효"라는 계정 단위 커트라인을 Redis에 저장하는 저장소.
 * key 포맷은 RefreshTokenRepository와 맞춰 "accessTokenValidAfter:{role}:{id}".
 * [LG-fm 컨벤션 리팩토링] common.auth.jwt로 이동, 로직 무변경 — 설계 배경은 이전 위치의 커밋 이력 참고
 * (boolean 블랙리스트 대신 커트라인 시각을 쓰는 이유, DB 백업을 두지 않는 이유, fail-open 정책).
 */
@Repository
@RequiredArgsConstructor
public class AccessTokenValidAfterRepository {

    private static final String KEY_PREFIX = "accessTokenValidAfter:";

    private final StringRedisTemplate redisTemplate;

    public void invalidateBefore(String role, Long id, LocalDateTime cutoff, Duration ttl) {
        redisTemplate.opsForValue().set(key(role, id), cutoff.toString(), ttl);
    }

    public boolean isValidAfter(String role, Long id, LocalDateTime tokenIssuedAt) {
        String stored = redisTemplate.opsForValue().get(key(role, id));
        if (stored == null) {
            return true;
        }
        LocalDateTime cutoff = LocalDateTime.parse(stored);
        return !tokenIssuedAt.isBefore(cutoff);
    }

    private String key(String role, Long id) {
        return KEY_PREFIX + role + ":" + id;
    }
}
