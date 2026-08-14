package com.example.freshdemo.member.oauth;

import com.example.freshdemo.member.domain.Member;
import com.example.freshdemo.member.domain.MemberRole;
import com.example.freshdemo.member.domain.SocialType;
import java.util.Map;
import lombok.Builder;

/**
 * 카카오 OIDC 응답에서 필요한 값만 뽑아내는 어댑터. sub(식별자) 말고는 아무것도 안 뽑는다 —
 * email/name/nickname 전부 카카오 대신 온보딩 폼으로 받기로 했다(Member.email 필드 주석,
 * 요구사항 예외사항의 "카카오 oidc는 로그인 전용, 개인정보는 폼으로 입력받기" 참고). 그래서
 * attributes/nameAttributeKey를 여전히 들고 있긴 하지만 providerUserId 추출 용도일 뿐이다.
 *
 * 필드명(provider/providerUserId)은 목표 DDL(member.provider/provider_user_id)과 Member 엔티티에
 * 맞췄다(예전엔 socialType/socialTypeId).
 *
 * 다른 소셜을 추가하면 SocialType에 값 추가 + 여기 of()에 분기 추가 + ofXxx() 메서드 추가하면 된다.
 */
@Builder
public record OAuthAttributes(
        Map<String, Object> attributes,
        String nameAttributeKey, // 카카오는 "sub" (application.yml의 user-name-attribute 참고)
        SocialType provider,
        String providerUserId
) {

    public static OAuthAttributes of(SocialType provider, String userNameAttributeName, Map<String, Object> attributes) {
        return switch (provider) {
            case KAKAO -> ofKakao(provider, userNameAttributeName, attributes);
        };
    }

    private static OAuthAttributes ofKakao(SocialType provider, String userNameAttributeName, Map<String, Object> attributes) {
        return OAuthAttributes.builder()
                .attributes(attributes)
                .nameAttributeKey(userNameAttributeName)
                .provider(provider)
                .providerUserId(String.valueOf(attributes.get(userNameAttributeName)))
                .build();
    }

    public Member toEntity(Long memberGradeId) {
        return Member.builder()
                .provider(provider)
                .providerUserId(providerUserId)
                .role(MemberRole.ROLE_USER)
                .memberGradeId(memberGradeId)
                .build();
    }
}
