package com.example.freshdemo.common.auth.jwt;

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
 * Access/Refresh 토큰 생성·파싱·검증 담당. member/admin 공용 인증 인프라라 common.auth 소속.
 * role 클레임은 Spring Security 권한 문자열 그대로("ROLE_USER", "ROLE_ADMIN", "ROLE_SUPER_ADMIN")를
 * 담는다 — MemberRole.name(), AdminRole.toAuthority() 양쪽 다 이 포맷으로 맞춰뒀다.
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

    public String createRefreshToken(Long id, TokenType type, String role, boolean remember) {
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
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

    public boolean getRemember(String token) {
        Boolean remember = parseClaims(token).get("remember", Boolean.class);
        return remember != null && remember;
    }

    public LocalDateTime getIssuedAt(String token) {
        Date issuedAt = parseClaims(token).getIssuedAt();
        return issuedAt == null ? null : LocalDateTime.ofInstant(issuedAt.toInstant(), ZoneId.systemDefault());
    }

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
