package com.example.freshdemo.member.domain.client;

import com.example.freshdemo.common.logging.PiiMasker;
import com.example.freshdemo.member.exception.MemberErrorCode;
import com.example.freshdemo.member.exception.MemberException;
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
 * [LG-fm 컨벤션 리팩토링] member.domain.client로 이동, 예외 타입만 변경.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KakaoUnlinkClient {

    private static final String UNLINK_URL = "https://kapi.kakao.com/v1/user/unlink";

    private final WebClient kakaoApiWebClient;

    @Value("${kakao.admin-key}")
    private String adminKey;

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
            log.warn("event=KAKAO_UNLINK_NON_FATAL status={} body={} kakaoUserId={}",
                    e.getStatusCode(), e.getResponseBodyAsString(), PiiMasker.maskProviderId(kakaoUserId));
        } catch (Exception e) {
            log.error("event=KAKAO_UNLINK_FAILED kakaoUserId={}", PiiMasker.maskProviderId(kakaoUserId), e);
            throw new MemberException(MemberErrorCode.KAKAO_UNLINK_FAILED, e);
        }
    }
}
