package com.example.freshdemo.member.domain.controller;

import com.example.freshdemo.common.logging.PiiMasker;
import com.example.freshdemo.member.domain.service.MemberWithdrawalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 카카오 연결 해제 웹훅 — 응답은 항상 200이어야 하는 카카오 스펙이라 ResponseEnvelope를 쓰지 않는다.
 * [LG-fm 컨벤션 리팩토링 2차] member(도메인 루트) -> member.domain.controller로 이동,
 * public -> package-private (MemberController와 같은 이유). 도메인 밖에서 이 클래스를 직접
 * 참조하는 곳은 없고 Spring이 요청을 라우팅할 뿐이라 package-private이어도 문제없다.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
class KakaoUnlinkWebhookController {

    private final MemberWithdrawalService memberWithdrawalService;

    @Value("${kakao.app-id}")
    private String ourKakaoAppId;

    @Value("${kakao.admin-key}")
    private String adminKey;

    @RequestMapping(value = "/webhook/kakao/unlink", method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<Void> handleUnlink(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestParam("app_id") String appId,
            @RequestParam("user_id") String userId,
            @RequestParam(value = "referrer_type", required = false) String referrerType
    ) {
        if (!ourKakaoAppId.equals(appId)) {
            log.warn("event=KAKAO_UNLINK_WEBHOOK_APP_ID_MISMATCH receivedAppId={} userId={}", appId, PiiMasker.maskProviderId(userId));
            return ResponseEntity.ok().build();
        }

        if (!("KakaoAK " + adminKey).equals(authorization)) {
            log.warn("event=KAKAO_UNLINK_WEBHOOK_AUTH_MISMATCH userId={}", PiiMasker.maskProviderId(userId));
            return ResponseEntity.ok().build();
        }

        log.info("event=KAKAO_UNLINK_WEBHOOK userId={} referrerType={}", PiiMasker.maskProviderId(userId), referrerType);

        try {
            memberWithdrawalService.withdrawByKakaoWebhook(userId);
        } catch (Exception e) {
            log.error("event=KAKAO_UNLINK_WEBHOOK_PROCESSING_FAILED userId={}", PiiMasker.maskProviderId(userId), e);
        }

        return ResponseEntity.ok().build();
    }
}
