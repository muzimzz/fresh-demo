package com.example.freshdemo.member.domain;

import com.example.freshdemo.member.MemberOAuth2LoginConfigurer;
import com.example.freshdemo.member.domain.oauth.CustomOidcUserService;
import com.example.freshdemo.member.domain.oauth.OAuth2LoginFailureHandler;
import com.example.freshdemo.member.domain.oauth.OAuth2LoginSuccessHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.oauth2.client.OAuth2LoginConfigurer;
import org.springframework.stereotype.Component;

/**
 * MemberOAuth2LoginConfigurer 구현체. domain 바로 아래에 package-private로 둔다(DPB-1-04,
 * DPB-6-01) — 기존에 SecurityConfig가 직접 들고 있던 3개 필드(CustomOidcUserService/
 * OAuth2LoginSuccessHandler/OAuth2LoginFailureHandler)를 그대로 여기로 옮겨 조립만 한다.
 */
@Component
@RequiredArgsConstructor
class MemberOAuth2LoginConfigurerImpl implements MemberOAuth2LoginConfigurer {

    private final CustomOidcUserService customOidcUserService;
    private final OAuth2LoginSuccessHandler oAuth2LoginSuccessHandler;
    private final OAuth2LoginFailureHandler oAuth2LoginFailureHandler;

    @Override
    public void configure(OAuth2LoginConfigurer<HttpSecurity> oauth2Login) {
        oauth2Login
                .userInfoEndpoint(userInfo -> userInfo.oidcUserService(customOidcUserService))
                .successHandler(oAuth2LoginSuccessHandler)
                .failureHandler(oAuth2LoginFailureHandler);
    }
}
