package com.example.freshdemo.member.domain;

public enum MemberStatus {
    /** 카카오 최초 로그인 직후 — 필수 추가정보(휴대전화/주소)를 아직 안 받은 상태. */
    PENDING_PROFILE,
    ACTIVE,
    WITHDRAWN,
}
