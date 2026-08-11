package com.example.freshdemo.member.controller;

import com.example.freshdemo.common.logging.PiiMasker;
import com.example.freshdemo.member.service.MemberWithdrawalService;
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
 * 사용자가 "우리 서비스 밖"(카카오톡 설정, 카카오계정 페이지, 카카오계정 탈퇴 등)에서
 * 먼저 연결을 끊었을 때 카카오가 호출해주는 웹훅. 카카오 디벨로퍼스 콘솔의
 * [앱] > [웹훅] > [연결 해제 웹훅]에 이 컨트롤러의 실제 경로(예: https://yourdomain.com/api/webhook/kakao/unlink)를
 * 등록해야 실제로 호출이 들어온다.
 *
 * 주의:
 *   - 우리가 먼저 KakaoUnlinkClient로 unlink API를 호출한 경우엔 이 웹훅이 오지 않는다
 *     (카카오 문서에 명시됨) — 그래서 MemberWithdrawalService에 별도 메서드(withdrawByKakaoWebhook)를 둠.
 *   - referrer_type으로 어떤 경로였는지 구분 가능(UNLINK_FROM_APPS/ACCOUNT_DELETE/FORCED_ACCOUNT_DELETE/...).
 *     지금은 구분 없이 전부 탈퇴 처리하지만, 나중에 필요하면 분기해서 로깅/정책을 다르게 가져가면 된다.
 *   - 응답은 회원이 없거나 오류가 나도 반드시 200이어야 한다(카카오 스펙) — 그래서 예외를 던지지 않는다.
 *   - app_id는 비밀값이 아니라 위조 가능하므로 그것만으론 인증이 안 된다. 카카오 공식 문서 확인 결과,
 *     이 웹훅 요청에는 Authorization 헤더로 "KakaoAK {대표 어드민 키}"가 함께 실려 온다 — 우리만 아는
 *     값이라 이걸로 실제 카카오발 요청인지를 최소한이나마 검증할 수 있다(app_id는 스팸/오탐 필터링용,
 *     admin key는 진위 확인용, 두 단계로 걸러낸다).
 *   - 더 강한 방식으로 카카오는 SSF(Shared Signals Framework) 기반의 "계정 상태 변경 웹훅"도 제공한다 —
 *     RS256 서명된 JWT(Security Event Token)를 공개키(JWKS)로 검증하는 방식. 지금 쓰는 건 구형(레거시)
 *     연결 해제 웹훅이고, 나중에 정식 서비스로 갈 때 SSF 방식으로 옮기는 걸 고려할 것.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class KakaoUnlinkWebhookController {

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
            return ResponseEntity.ok().build(); // 스펙상 오류여도 200
        }

        // app_id는 남에게 노출될 수 있는 값이라 그것만으론 부족 — 우리만 아는 admin key가 그대로
        // 실려 왔는지까지 확인해야 "진짜 카카오가 보낸 요청"이라는 최소한의 근거가 생긴다.
        if (!("KakaoAK " + adminKey).equals(authorization)) {
            log.warn("event=KAKAO_UNLINK_WEBHOOK_AUTH_MISMATCH userId={}", PiiMasker.maskProviderId(userId));
            return ResponseEntity.ok().build();
        }

        log.info("event=KAKAO_UNLINK_WEBHOOK userId={} referrerType={}", PiiMasker.maskProviderId(userId), referrerType);

        try {
            memberWithdrawalService.withdrawByKakaoWebhook(userId);
        } catch (Exception e) {
            // 실패해도 200을 줘야 카카오가 재시도 폭주를 안 일으킨다 — 대신 에러는 반드시 로그로 남긴다
            log.error("event=KAKAO_UNLINK_WEBHOOK_PROCESSING_FAILED userId={}", PiiMasker.maskProviderId(userId), e);
        }

        return ResponseEntity.ok().build();
    }
}
