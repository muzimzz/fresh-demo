package com.example.freshdemo.member.client;

import com.example.freshdemo.common.logging.PiiMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 서버(Admin Key) 주도로 카카오 쪽 access token을 무효화한다. 연결 자체는 유지된다는 점에서
 * KakaoUnlinkClient(연결 완전 해제)와 다르다.
 * https://kapi.kakao.com/v1/user/logout, target_id_type=user_id + target_id(카카오 회원번호)
 *
 * 우리는 카카오의 access token을 저장하지도, 클라이언트에 넘기지도 않지만(의도적으로 버림), 이 호출은
 * "우리 앱 이름으로 그 회원에게 발급된" 모든 카카오 토큰을 서버 주도로 무효화한다 — 우리가 몰랐던
 * 토큰(예: 프론트가 카카오 SDK를 다른 용도로 직접 써서 별도로 받은 토큰)까지 정리되는 효과가 있어서,
 * 우리 쪽 로그아웃에 방어적으로 얹어준다. "카카오계정과 함께 로그아웃"(브라우저 기반, GET /oauth/logout)
 * 과는 다른 것 — 그건 여전히 구현하지 않는다.
 *
 * 부가 조치라 실패해도 우리 서비스 로그아웃 자체를 막지 않는다 — 우리 쪽 토큰은 이미 지워진 뒤이므로
 * 이 호출이 실패해도 사용자 입장에선 정상적으로 로그아웃된 상태다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KakaoLogoutClient {

    private static final String LOGOUT_URL = "https://kapi.kakao.com/v1/user/logout";

    private final WebClient kakaoApiWebClient;

    @Value("${kakao.admin-key}")
    private String adminKey;

    /**
     * @param kakaoUserId Member.socialTypeId (카카오 회원번호)
     */
    public void logout(String kakaoUserId) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("target_id_type", "user_id");
        form.add("target_id", kakaoUserId);

        try {
            kakaoApiWebClient.post()
                    .uri(LOGOUT_URL)
                    .header("Authorization", "KakaoAK " + adminKey)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .bodyValue(form)
                    .retrieve()
                    .toBodilessEntity()
                    .block();
        } catch (Exception e) {
            log.warn("event=KAKAO_LOGOUT_FAILED kakaoUserId={} err={}", PiiMasker.maskProviderId(kakaoUserId), e.toString());
        }
    }
}
