package com.example.freshdemo.member.domain.service;

/** DB 탈퇴 커밋 후에만 카카오 unlink를 호출하기 위한 이벤트. */
public record MemberWithdrawalEvent(Long memberId, String kakaoUserId) {
}
