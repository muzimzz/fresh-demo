package com.example.freshdemo.member.controller;

import com.example.freshdemo.auth.CustomUserDetails;
import com.example.freshdemo.auth.jwt.AuthCookieFactory;
import com.example.freshdemo.common.response.ApiResponse;
import com.example.freshdemo.member.service.MemberWithdrawalService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 회원탈퇴 API. 실제 경로 /api/members/me (DELETE). */
@RestController
@RequestMapping("/members")
@RequiredArgsConstructor
public class MemberWithdrawalController {

    private final MemberWithdrawalService memberWithdrawalService;
    private final AuthCookieFactory authCookieFactory;

    @DeleteMapping("/me")
    public ResponseEntity<ApiResponse<Void>> withdraw(@AuthenticationPrincipal CustomUserDetails userDetails, HttpServletResponse response) {

        memberWithdrawalService.withdraw(userDetails.getId());

        // 탈퇴 직후 클라이언트가 들고 있는 쿠키도 바로 지워준다.
        // (accessToken 자체는 블랙리스트로도 막히지만, 쿠키를 안 지우면 프론트가 계속 들고 있다가
        //  괜히 매 요청마다 막힌 토큰을 보내게 되니 같이 정리)
        response.addHeader(HttpHeaders.SET_COOKIE, authCookieFactory.expiredAccessTokenCookie().toString());
        response.addHeader(HttpHeaders.SET_COOKIE, authCookieFactory.expiredRefreshTokenCookie().toString());

        return ResponseEntity.ok(ApiResponse.of(null));
    }
}
