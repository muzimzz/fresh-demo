package com.example.freshdemo.member.domain;

/**
 * 카카오만 지원. 나중에 다른 소셜 로그인을 추가하면 여기에 값만 늘리면 되고,
 * OAuthAttributes.of()의 switch에도 분기를 추가하면 된다.
 */
public enum SocialType {
    KAKAO,
}
