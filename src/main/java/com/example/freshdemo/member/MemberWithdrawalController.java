package com.example.freshdemo.member;

import com.example.freshdemo.common.auth.AuthCookieFactory;
import com.example.freshdemo.common.auth.CustomUserDetails;
import com.example.freshdemo.common.response.ResponseEnvelope;
import com.example.freshdemo.member.domain.service.MemberWithdrawalService;
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
    public ResponseEntity<ResponseEnvelope<Void>> withdraw(@AuthenticationPrincipal CustomUserDetails userDetails, HttpServletResponse response) {

        memberWithdrawalService.withdraw(userDetails.getId());

        response.addHeader(HttpHeaders.SET_COOKIE, authCookieFactory.expiredAccessTokenCookie().toString());
        response.addHeader(HttpHeaders.SET_COOKIE, authCookieFactory.expiredRefreshTokenCookie().toString());

        return ResponseEntity.ok(ResponseEnvelope.success());
    }
}
