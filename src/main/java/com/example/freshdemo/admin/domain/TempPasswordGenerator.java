package com.example.freshdemo.admin.domain;

import java.security.SecureRandom;

/**
 * [ArchUnit 대응] admin.domain.service -> admin.domain으로 이동. 계산 헬퍼라 domain.service
 * 패키지(커버리지 100% 대상)에 있으면 안 된다 — 원래 domain.service 안에서만 쓰이는
 * package-private였는데, 패키지가 바뀌면서 admin.domain.service.AdminService가 계속 쓰려면
 * public이어야 해서 접근 제어자를 넓혔다.
 */
public final class TempPasswordGenerator {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String LOWER = "abcdefghijkmnpqrstuvwxyz";
    private static final String DIGITS = "23456789";
    private static final String SPECIAL = "!@#$%^&*";
    private static final String ALL = UPPER + LOWER + DIGITS + SPECIAL;
    private static final int LENGTH = 12;

    private TempPasswordGenerator() {
    }

    public static String generate() {
        char[] chars = new char[LENGTH];
        chars[0] = pick(UPPER);
        chars[1] = pick(LOWER);
        chars[2] = pick(DIGITS);
        chars[3] = pick(SPECIAL);
        for (int i = 4; i < LENGTH; i++) {
            chars[i] = pick(ALL);
        }
        for (int i = chars.length - 1; i > 0; i--) {
            int j = RANDOM.nextInt(i + 1);
            char tmp = chars[i];
            chars[i] = chars[j];
            chars[j] = tmp;
        }
        return new String(chars);
    }

    private static char pick(String pool) {
        return pool.charAt(RANDOM.nextInt(pool.length()));
    }
}
