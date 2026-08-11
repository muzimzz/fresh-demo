package com.example.freshdemo.member.client;

import com.example.freshdemo.common.exception.BusinessException;
import com.example.freshdemo.common.exception.ErrorCode;
import com.example.freshdemo.common.logging.PiiMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * 서버(Admin Key) 주도로 카카오 계정과의 연결을 끊는 클라이언트.
 * https://kapi.kakao.com/v1/user/unlink, target_id_type=user_id + target_id(카카오 회원번호)
 *
 * 유저의 accessToken을 안 쓰는 이유: 로그인 직후 우리가 그 토큰을 저장해두지 않기 때문(의도적으로 버림).
 * 그래서 탈퇴 시점엔 Admin Key로 서버 간(server-to-server) 강제 해제 방식을 쓴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KakaoUnlinkClient {

    private static final String UNLINK_URL = "https://kapi.kakao.com/v1/user/unlink";

    private final WebClient kakaoApiWebClient;

    @Value("${kakao.admin-key}")
    private String adminKey;

    /**
     * @param kakaoUserId Member.socialTypeId (카카오 회원번호)
     */
    public void unlink(String kakaoUserId) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("target_id_type", "user_id");
        form.add("target_id", kakaoUserId);

        try {
            kakaoApiWebClient.post()
                    .uri(UNLINK_URL)
                    .header("Authorization", "KakaoAK " + adminKey)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .bodyValue(form)
                    .retrieve()
                    .toBodilessEntity()
                    .block();
        } catch (WebClientResponseException e) {
            // 이미 카카오 쪽에서 먼저 끊긴 경우(예: 사용자가 카카오에서 직접 연결 해제한 뒤 우리 탈퇴 버튼을 누른 경우)
            // 카카오는 보통 404/400을 준다 — 결과적으로 원하는 상태(연결 안 됨)이므로 여기선 실패로 취급하지 않는다.
            log.warn("event=KAKAO_UNLINK_NON_FATAL status={} body={} kakaoUserId={}",
                    e.getStatusCode(), e.getResponseBodyAsString(), PiiMasker.maskProviderId(kakaoUserId));
        } catch (Exception e) {
            log.error("event=KAKAO_UNLINK_FAILED kakaoUserId={}", PiiMasker.maskProviderId(kakaoUserId), e);
            throw new BusinessException(ErrorCode.KAKAO_UNLINK_FAILED, e);
        }
    }
}
