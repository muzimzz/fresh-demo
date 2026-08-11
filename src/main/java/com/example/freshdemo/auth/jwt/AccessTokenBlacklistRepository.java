package com.example.freshdemo.auth.jwt;

import java.time.Duration;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * 탈퇴(회원)/계정 삭제(관리자) 시점에 이미 발급된 accessToken을 즉시 무효화하기 위한 블랙리스트.
 * key 포맷을 RefreshTokenRepository와 맞춰 "blacklist:{role}:{id}"로 통일했다.
 */
@Repository
@RequiredArgsConstructor
public class AccessTokenBlacklistRepository {

    private static final String KEY_PREFIX = "blacklist:";

    private final StringRedisTemplate redisTemplate;

    public void blacklist(String role, UUID id, Duration ttl) {
        redisTemplate.opsForValue().set(key(role, id), "1", ttl);
    }

    public boolean isBlacklisted(String role, UUID id) {
        return redisTemplate.hasKey(key(role, id));
    }

    private String key(String role, UUID id) {
        return KEY_PREFIX + role + ":" + id;
    }
}
