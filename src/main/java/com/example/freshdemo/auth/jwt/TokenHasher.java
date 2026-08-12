package com.example.freshdemo.auth.jwt;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * refreshToken처럼 "저장은 해시로 해두고, 나중엔 비교만 하면 되는" 고엔트로피 랜덤 토큰을 해싱하는 유틸.
 *
 * 비밀번호(PasswordEncoder/bcrypt)와는 다른 이유로 다른 알고리즘을 쓴다 — 비밀번호는 사람이 짧고
 * 예측 가능하게 만들어서(저엔트로피) salt와 의도적으로 느린 해시가 브루트포스/레인보우테이블 방어에
 * 꼭 필요하지만, JWT는 서명 자체가 이미 충분히 무작위(고엔트로피)라 그런 공격이 현실적으로
 * 불가능하다 — 그래서 빠른 SHA-256이면 충분하고, 굳이 bcrypt처럼 느리게 만들 이유가 없다(요청마다
 * 검증해야 하는 값이라 오히려 느리면 성능에 안 좋다).
 */
public final class TokenHasher {

    private TokenHasher() {
    }

    public static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256은 모든 JDK가 표준으로 제공하는 알고리즘이라 실제로는 발생하지 않는다.
            throw new IllegalStateException("SHA-256 알고리즘을 찾을 수 없음", e);
        }
    }
}
