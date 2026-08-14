package com.example.freshdemo.member.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 카카오 최초 로그인(PENDING_PROFILE) 이후 필수 온보딩 정보를 채우는 요청.
 * 휴대전화/주소(선택 항목)는 여기서 안 받는다 — 첫 배송 시점에 별도로 받기로 함(주문 도메인이
 * 생겨야 걸 수 있는 지점이라 이 프로젝트 범위 밖). 필수는 이름 + 이메일 + 닉네임 + 약관 동의다.
 *
 * name(실명, 폼 입력)은 카카오가 주는 nickname과 별개 필드다 — 목표 DDL을 따라 온보딩 필수로
 * 바로 받기로 했다(Member.name 참고).
 *
 * email도 여기서 받는다 — 목표 DDL 코멘트는 "카카오 제공 이메일"이지만, 카카오에서 받지 않고
 * 온보딩 폼 입력값을 쓰기로 했다(Member.email 참고). 카카오 OIDC는 로그인 전용으로만 쓴다.
 *
 * termsAgreed는 @AssertTrue라서 false로 오면 컨트롤러 진입 전에 검증 실패(400)로 막힌다 —
 * "필수 동의 거부 시 가입 불가"를 서비스 로직이 아니라 검증 단계에서 처리하는 셈.
 */
public record MemberOnboardingRequest(
        @NotBlank @Size(max = 50) String name,
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Size(max = 50) String nickname, // Member.nickname 목표 DDL 길이(VARCHAR(50))에 맞춤
        @AssertTrue(message = "약관에 동의해야 가입할 수 있습니다.") boolean termsAgreed,
        // 선택 항목이라 검증 어노테이션 없음. 필드 자체를 안 보내면(JSON에 없으면) Jackson이
        // boolean 기본값 false로 채운다 — "명시적으로 동의 안 함"과 "그냥 안 보냄"을 구분하지 않는다.
        boolean marketingAgreed
) {
}
