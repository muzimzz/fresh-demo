package com.example.freshdemo.member.oauth;

import com.example.freshdemo.member.domain.Member;
import com.example.freshdemo.member.domain.MemberRole;
import com.example.freshdemo.member.domain.SocialType;
import java.util.Map;
import lombok.Builder;

/**
 * 카카오 OIDC 응답에서 필요한 값만 뽑아내는 어댑터.
 * 다른 소셜을 추가하면 SocialType에 값 추가 + 여기 of()에 분기 추가 + ofXxx() 메서드 추가하면 된다.
 */
@Builder
public record OAuthAttributes(
        Map<String, Object> attributes,
        String nameAttributeKey, // 카카오는 "sub" (application.yml의 user-name-attribute 참고)
        SocialType socialType,
        String socialTypeId,
        String email
) {

    public static OAuthAttributes of(SocialType socialType, String userNameAttributeName, Map<String, Object> attributes) {
        return switch (socialType) {
            case KAKAO -> ofKakao(socialType, userNameAttributeName, attributes);
        };
    }

    private static OAuthAttributes ofKakao(SocialType socialType, String userNameAttributeName, Map<String, Object> attributes) {
        return OAuthAttributes.builder()
                .attributes(attributes)
                .nameAttributeKey(userNameAttributeName)
                .socialType(socialType)
                .socialTypeId(String.valueOf(attributes.get(userNameAttributeName)))
                .email((String) attributes.get("email"))
                .build();
    }

    public Member toEntity(Long memberGradeId) {
        return Member.builder()
                .socialType(socialType)
                .socialTypeId(socialTypeId)
                .email(email)
                .role(MemberRole.ROLE_USER)
                .memberGradeId(memberGradeId)
                .build();
    }
}
