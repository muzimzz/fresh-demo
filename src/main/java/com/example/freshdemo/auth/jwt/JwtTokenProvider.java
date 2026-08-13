package com.example.freshdemo.auth.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Access/Refresh 토큰 생성·파싱·검증 담당.
 *
 * 원래 member 패키지 소속(member.jwt)이었는데, 관리자(Admin) 로그인도 같은 발급/검증 로직을
 * 그대로 재사용하기로 하면서 member 전용이 아닌 공용 인증 인프라로 auth 패키지로 옮겼다.
 * 클레임도 member 전용(sub=memberId, role)에서 type(MEMBER/ADMIN) + role(권한 문자열)로 일반화했다.
 *
 * role 클레임은 Spring Security 권한 문자열 그대로("ROLE_USER", "ROLE_ADMIN", "ROLE_SUPER_ADMIN")를
 * 담는다 — MemberRole.name(), AdminRole.toAuthority() 양쪽 다 이 포맷으로 맞춰뒀다(CustomUserDetails 참고).
 */
@Component
public class JwtTokenProvider {

    private final SecretKey secretKey;
    private final long accessTokenValidityMs;
    private final long refreshTokenValidityMs;

    public JwtTokenProvider(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.access-token-validity-ms}") long accessTokenValidityMs,
            @Value("${jwt.refresh-token-validity-ms}") long refreshTokenValidityMs
    ) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes());
        this.accessTokenValidityMs = accessTokenValidityMs;
        this.refreshTokenValidityMs = refreshTokenValidityMs;
    }

    public String createAccessToken(Long id, TokenType type, String role) {
        return Jwts.builder()
                .subject(String.valueOf(id))
                .claim("type", type.name())
                .claim("role", role)
                .issuedAt(new Date(System.currentTimeMillis()))
                .expiration(new Date(System.currentTimeMillis() + accessTokenValidityMs))
                .signWith(secretKey)
                .compact();
    }

    /**
     * Refresh 토큰에도 type/role을 같이 담는다 — /auth/reissue가 DB를 먼저 보지 않고도
     * 토큰만으로 Redis 키(refreshToken:{role}:{id})를 알아낼 수 있어야 하기 때문.
     *
     * remember는 "자동로그인" 체크 여부 — 토큰 자체의 만료시간(14일)은 바꾸지 않고,
     * AuthCookieFactory가 쿠키를 영속 쿠키로 내려줄지 세션 쿠키(브라우저 종료 시 삭제)로
     * 내려줄지 결정하는 데만 쓴다. reissue 때도 같은 값을 이어가야 해서 토큰에 실어둔다
     * (서버가 "이 세션이 remember-me였는지"를 따로 저장하지 않아도 되게).
     *
     * jti(표준 클레임, RFC 7519)는 "이 토큰 인스턴스"를 가리키는 랜덤 식별자다. RT는 회전(RTR)
     * 때마다 매번 새로 발급되니, 옛 값이 다시 들어오면(재사용 의심) 로그에 "정확히 어떤 토큰
     * 인스턴스였는지" 남기는 용도로 쓴다(AuthController.reissue()의 REFRESH_TOKEN_REUSE_SUSPECTED
     * 참고). jti는 서명 없이 그 자체로는 아무 권한도 증명하지 못하는 순수 라벨이라 로그에 평문으로
     * 남겨도 안전하다 — accessToken/refreshToken 원문 문자열 전체(서명까지 포함된 것)를 로그에
     * 남기면 안 되는 것과는 다른 얘기다. AT는 재사용 감지 대상이 아니라서(같은 AT를 수명 내내
     * 반복 사용하는 게 정상 동작) jti를 안 넣는다 — 지금 쓸 곳이 없는 클레임을 미리 넣어두지 않음.
     */
    public String createRefreshToken(Long id, TokenType type, String role, boolean remember) {
        return Jwts.builder()
                .id(UUID.randomUUID().toString()) // jti: 회원/관리자 id와 무관한 순수 랜덤 토큰 인스턴스 식별자 — PK가 Long이 돼도 그대로 UUID
                .subject(String.valueOf(id))
                .claim("type", type.name())
                .claim("role", role)
                .claim("remember", remember)
                .issuedAt(new Date(System.currentTimeMillis()))
                .expiration(new Date(System.currentTimeMillis() + refreshTokenValidityMs))
                .signWith(secretKey)
                .compact();
    }

    public Claims parseClaims(String token) {
        return Jwts.parser().verifyWith(secretKey).build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean validateToken(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public Long getId(String token) {
        return Long.valueOf(parseClaims(token).getSubject());
    }

    public TokenType getType(String token) {
        String type = parseClaims(token).get("type", String.class);
        return type == null ? null : TokenType.valueOf(type);
    }

    public String getRole(String token) {
        return parseClaims(token).get("role", String.class);
    }

    /** claim이 없는(예전 형식) 토큰은 false로 취급 — 세션 쿠키로 보수적으로 처리. */
    public boolean getRemember(String token) {
        Boolean remember = parseClaims(token).get("remember", Boolean.class);
        return remember != null && remember;
    }

    /** accessToken 발급 시각(iat). AccessTokenValidAfterRepository의 커트라인과 비교하는 데 쓴다. */
    public LocalDateTime getIssuedAt(String token) {
        Date issuedAt = parseClaims(token).getIssuedAt();
        return issuedAt == null ? null : LocalDateTime.ofInstant(issuedAt.toInstant(), ZoneId.systemDefault());
    }

    /** refreshToken의 jti(토큰 인스턴스 식별자). AT엔 없으므로 AT에 호출하면 null. */
    public String getJti(String token) {
        return parseClaims(token).getId();
    }

    public long getAccessTokenValidityMs() {
        return accessTokenValidityMs;
    }

    public long getRefreshTokenValidityMs() {
        return refreshTokenValidityMs;
    }
}
