package com.example.freshdemo.member;

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
 */
@RestController
@RequestMapping("/members")
@RequiredArgsConstructor
public class MemberController {

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
