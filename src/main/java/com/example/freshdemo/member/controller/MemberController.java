package com.example.freshdemo.member.controller;

import com.example.freshdemo.auth.CustomUserDetails;
import com.example.freshdemo.common.response.ApiResponse;
import com.example.freshdemo.member.domain.Member;
import com.example.freshdemo.member.dto.MemberOnboardingRequest;
import com.example.freshdemo.member.dto.MemberResponse;
import com.example.freshdemo.member.service.MemberOnboardingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 회원 프로필 API. 실제 경로 /api/members/**. 탈퇴는 별도 MemberWithdrawalController가 담당. */
@RestController
@RequestMapping("/members")
@RequiredArgsConstructor
public class MemberController {

    private final MemberOnboardingService memberOnboardingService;

    /**
     * 카카오 최초 로그인(PENDING_PROFILE) 이후 필수 온보딩 정보(이름+닉네임+약관동의)를 채워 ACTIVE로 넘긴다.
     * 이 값이 채워지기 전까진 로그인 리다이렉트의 pendingProfile=true가 계속 내려간다 — 프론트는
     * 그 신호를 보고 로그인할 때마다 이 화면으로 강제 리다이렉트하면 된다.
     */
    @PatchMapping("/me/onboarding")
    public ResponseEntity<ApiResponse<MemberResponse>> completeOnboarding(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestBody @Valid MemberOnboardingRequest request
    ) {
        Member member = memberOnboardingService.completeOnboarding(
                userDetails.getId(), request.name(), request.nickname(), request.marketingAgreed());
        return ResponseEntity.ok(ApiResponse.of(MemberResponse.from(member)));
    }
}
