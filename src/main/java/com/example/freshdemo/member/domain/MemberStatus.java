package com.example.freshdemo.member.domain;

public enum MemberStatus {
    /** 카카오 최초 로그인 직후 — 필수 추가정보(휴대전화/주소)를 아직 안 받은 상태. */
    PENDING_PROFILE,
    ACTIVE,
    /**
     * 관리자에 의한 이용 제한. 목표 DDL에 맞춰 값만 추가한 상태 — 누가/언제/어떤 기준으로
     * 이 상태로 전이시키는지(관리자 API, 자동 정책 등)는 아직 없다. 이 상태로 바꾸는 흐름 자체를
     * 설계/구현하기 전까지는 어떤 코드도 이 값을 세팅하지 않는다.
     */
    BLOCKED,
    WITHDRAWN,
}
