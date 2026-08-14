package com.example.freshdemo.admin.service;

import java.security.SecureRandom;

/**
 * 관리자 계정 발급 시 임시 비밀번호를 만드는 유틸. identifier-strategy-guideline.md가 이미
 * 정한 "추측 방지가 필요한 값은 SecureRandom"이라는 원칙을 그대로 따른다 — Math.random()/Random은
 * 시드가 예측 가능해서 이런 용도에 못 쓴다.
 *
 * 대소문자+숫자+특수문자 각 최소 1개를 보장하고 나머지는 전체 후보군에서 무작위로 채운 뒤 셔플한다
 * — 그냥 전체 후보군에서 12자를 뽑으면 특수문자가 하나도 안 나올 확률이 낮지 않아서, 로그인 폼에
 * 흔히 있는 "영문+숫자+특수문자 포함" 같은 정책을 우연히 어길 수 있기 때문이다.
 */
final class TempPasswordGenerator {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ"; // 헷갈리는 I/O 제외
    private static final String LOWER = "abcdefghijkmnpqrstuvwxyz"; // 헷갈리는 l/o 제외
    private static final String DIGITS = "23456789"; // 헷갈리는 0/1 제외
    private static final String SPECIAL = "!@#$%^&*";
    private static final String ALL = UPPER + LOWER + DIGITS + SPECIAL;
    private static final int LENGTH = 12;

    private TempPasswordGenerator() {
    }

    static String generate() {
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
