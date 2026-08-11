package com.example.freshdemo.member.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 카카오 최초 로그인(PENDING_PROFILE) 이후 필수 온보딩 정보를 채우는 요청.
 * 휴대전화/주소(선택 항목)는 여기서 안 받는다 — 첫 배송 시점에 별도로 받기로 함(주문 도메인이
 * 생겨야 걸 수 있는 지점이라 이 프로젝트 범위 밖). 필수는 닉네임 + 약관 동의뿐이다.
 *
 * termsAgreed는 @AssertTrue라서 false로 오면 컨트롤러 진입 전에 검증 실패(400)로 막힌다 —
 * "필수 동의 거부 시 가입 불가"를 서비스 로직이 아니라 검증 단계에서 처리하는 셈.
 */
public record MemberOnboardingRequest(
        @NotBlank @Size(max = 20) String nickname,
        @AssertTrue(message = "약관에 동의해야 가입할 수 있습니다.") boolean termsAgreed
) {
}
