package com.example.freshdemo.member.domain.controller;

import com.example.freshdemo.common.auth.CustomUserDetails;
import com.example.freshdemo.common.response.ResponseEnvelope;
import com.example.freshdemo.member.domain.entity.Member;
import com.example.freshdemo.member.domain.service.MemberOnboardingService;
import com.example.freshdemo.member.domain.service.MemberProfileUpdateService;
import com.example.freshdemo.member.dto.MemberOnboardingRequest;
import com.example.freshdemo.member.dto.MemberProfileUpdateRequest;
import com.example.freshdemo.member.dto.MemberResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 회원 프로필 API. 실제 경로 /api/members/**. 탈퇴는 별도 MemberWithdrawalController가 담당.
 * [LG-fm 컨벤션 리팩토링] member.controller -> member(도메인 루트)로 이동, ApiResponse ->
 * ResponseEnvelope로 응답 타입 교체.
 *
 * [LG-fm 컨벤션 리팩토링 2차] member(도메인 루트) -> member.domain.controller로 재이동,
 * public -> package-private. domain-package-boundary-guideline.md 원칙상 도메인 루트에는
 * ~Api 인터페이스/공개 DTO(record)/공개 예외만 두고 Controller는 domain 하위에 둬야 한다
 * (ArchUnit layeredArchitecture: Controller layer = ..domain.controller.., rootIsContractOnly).
 * 컨트롤러는 어떤 계층에서도 호출되지 않는 진입점이라 "다른 도메인에 공개하는 계약"이 아니다.
 */
@RestController
@RequestMapping("/members")
@RequiredArgsConstructor
class MemberController {

    private final MemberOnboardingService memberOnboardingService;
    private final MemberProfileUpdateService memberProfileUpdateService;

    @PatchMapping("/me/onboarding")
    public ResponseEntity<ResponseEnvelope<MemberResponse>> completeOnboarding(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestBody @Valid MemberOnboardingRequest request
    ) {
        Member member = memberOnboardingService.completeOnboarding(
                userDetails.getId(), request.name(), request.email(), request.nickname(), request.marketingAgreed());
        return ResponseEntity.ok(ResponseEnvelope.success(MemberResponse.from(member)));
    }

    @PatchMapping("/me")
    public ResponseEntity<ResponseEnvelope<MemberResponse>> updateProfile(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestBody @Valid MemberProfileUpdateRequest request
    ) {
        Member member = memberProfileUpdateService.updateProfile(
                userDetails.getId(), request.name(), request.email(), request.nickname(), request.phone(), request.address());
        return ResponseEntity.ok(ResponseEnvelope.success(MemberResponse.from(member)));
    }
}
