package com.example.freshdemo.member.service;

/**
 * DB상 탈퇴 처리(commit)가 끝난 뒤에만 카카오 unlink를 호출하기 위한 이벤트.
 * AFTER_COMMIT에서 소비된다 — 아직 커밋 안 된 탈퇴를 카카오에 먼저 통보해버리는 걸 막기 위함
 * (트랜잭션이 나중에 롤백되면 "DB엔 탈퇴 안 됐는데 카카오 연결만 끊긴" 불일치 상태가 생길 수 있어서).
 */
public record MemberWithdrawalEvent(Long memberId, String kakaoUserId) {
}
