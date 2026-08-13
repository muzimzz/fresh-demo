package com.example.freshdemo.auth.jwt;

import java.time.Duration;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * "이 시각 이전에 발급된 accessToken은 전부 무효"라는 계정 단위 커트라인을 Redis에 저장하는 저장소.
 * key 포맷은 RefreshTokenRepository와 맞춰 "accessTokenValidAfter:{role}:{id}".
 *
 * 원래는 boolean 블랙리스트 플래그("TTL 동안 무조건 이 계정 전부 차단") 방식(AccessTokenBlacklistRepository)
 * 이었는데, 탈퇴/계정삭제처럼 "다시 로그인할 일이 없는" 케이스엔 맞아도 토큰탈취 의심/비밀번호
 * 변경처럼 "지금 바로 재로그인해서 새 세션을 받아야 하는" 케이스엔 안 맞았다 — boolean 플래그는
 * 새로 로그인해서 받은 새 토큰까지 TTL이 끝날 때까지 막아버리는 버그가 있었기 때문이다. 그래서
 * boolean 대신 커트라인 시각을 저장하고, 각 요청의 accessToken이 실제로 그 시각 이후에
 * 발급됐는지(iat 클레임, JwtTokenProvider.getIssuedAt())를 비교하는 방식으로 바꿨다 — 커트라인
 * 이후에 새로 발급된 토큰은 정상 통과된다.
 *
 * 탈퇴/계정삭제/토큰탈취 의심(RT 재사용 감지) 전부 이 하나의 메커니즘으로 통일했다 — cutoff=지금
 * 시각으로 호출하면 예전 boolean 블랙리스트와 결과가 동일하다(탈퇴는 애초에 재가입이 새 PK로
 * 새 행을 만드는 구조라 "재로그인 후 새 토큰까지 막히는" 문제 자체가 발생하지 않는다).
 *
 * DB 백업은 일부러 안 둔다 — RefreshTokenRepository와 달리 이건 인증이 필요한 "모든" 요청마다
 * 검사해야 해서, DB까지 매번 보게 하면 그 자체가 성능 병목이 된다. Redis 장애 시엔
 * JwtAuthenticationFilter가 fail-open(통과)으로 처리한다 — 이 체크는 서명/만료 검증 위에 얹는
 * 2차 방어선이지 1차 인증 수단이 아니라서, Redis 순간 장애 하나로 인증 API 전체가 막히는 것보다는
 * 그 순간만 이 방어선이 비활성화되는 쪽이 낫다는 판단.
 */
@Repository
@RequiredArgsConstructor
public class AccessTokenValidAfterRepository {

    private static final String KEY_PREFIX = "accessTokenValidAfter:";

    private final StringRedisTemplate redisTemplate;

    /**
     * cutoff 이전에 발급된 토큰을 전부 무효화한다. ttl은 accessToken 최대 수명만큼이면 충분하다 —
     * 그 이후엔 cutoff 이전 토큰들도 자기 exp로 이미 자연 만료됐을 것이기 때문에, 이 커트라인
     * 엔트리 자체를 계속 들고 있을 필요가 없다.
     */
    public void invalidateBefore(String role, Long id, LocalDateTime cutoff, Duration ttl) {
        redisTemplate.opsForValue().set(key(role, id), cutoff.toString(), ttl);
    }

    /** 커트라인이 없으면(한 번도 무효화된 적 없음) 항상 유효. 있으면 발급 시각이 커트라인 이후여야 유효. */
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
