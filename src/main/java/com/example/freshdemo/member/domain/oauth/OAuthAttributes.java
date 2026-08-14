package com.example.freshdemo.member.domain.oauth;

import com.example.freshdemo.member.domain.entity.Member;
import com.example.freshdemo.member.domain.entity.MemberRole;
import com.example.freshdemo.member.domain.entity.SocialType;
import java.util.Map;
import lombok.Builder;

/**
 * 카카오 OIDC 응답에서 필요한 값(sub)만 뽑아내는 어댑터.
 * [LG-fm 컨벤션 리팩토링] member.domain.oauth로 이동, Member.register() 팩토리 호출로 변경.
 */
@Builder
public record OAuthAttributes(
        Map<String, Object> attributes,
        String nameAttributeKey,
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
        return Member.register(provider, providerUserId, memberGradeId);
    }
}
