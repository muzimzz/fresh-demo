package com.example.freshdemo.member;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.oauth2.client.OAuth2LoginConfigurer;

/**
 * member 도메인이 config(공용 설정)에 공개하는 계약. "카카오 OAuth2 로그인을 어떻게 설정할지"는
 * member.domain.oauth 안의 CustomOidcUserService/OAuth2LoginSuccessHandler/
 * OAuth2LoginFailureHandler가 알아서 하고, config.SecurityConfig는 이 인터페이스 하나만 보면 된다.
 *
 * [LG-fm 컨벤션 리팩토링 2차] config.SecurityConfig가 member.domain.oauth의 구현체 3개를 직접
 * 필드로 주입받아 오던 것을 대체하며 신설 — "common은 도메인을 모른다"(config도 동일 원칙 적용,
 * ArchUnit common_은_도메인을_모른다)를 지키기 위함이다. CustomOidcUser가 Member 엔티티를 직접
 * 드는 것 자체는 바꾸지 않았다 — 그 클래스들은 원래도 member.domain 안이라 도메인 내부에서
 * 자기 엔티티를 쓰는 건 문제가 아니고, 문제는 config가 그 구현체들을 직접 알았다는 점뿐이었다.
 */
public interface MemberOAuth2LoginConfigurer {

    void configure(OAuth2LoginConfigurer<HttpSecurity> oauth2Login);
}
