package com.example.freshdemo.config;

import com.example.freshdemo.auth.jwt.AccessTokenBlacklistRepository;
import com.example.freshdemo.auth.jwt.JwtAuthenticationFilter;
import com.example.freshdemo.auth.jwt.JwtTokenProvider;
import com.example.freshdemo.auth.jwt.RememberMeRequestFilter;
import com.example.freshdemo.member.oauth.OAuth2LoginSuccessHandler;
import com.example.freshdemo.member.oauth.error.JwtAccessDeniedHandler;
import com.example.freshdemo.member.oauth.error.JwtAuthenticationEntryPoint;
import com.example.freshdemo.member.oauth.oidc.CustomOidcUserService;
import jakarta.servlet.DispatcherType;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * haeyaji의 SecurityConfig에서 카카오만 남기고, oauth2/naver/google 관련 설정을 제거한 버전.
 * ASYNC/ERROR dispatcher permitAll 부분은 이 프로젝트에서 겪었던 SSE 재디스패치 403/500 버그의
 * 해결책이라 그대로 가져왔다 — SSE(또는 비슷한 비동기 스트리밍 응답)를 쓸 계획이 없다면 없어도 무방하지만,
 * 나중에 추가할 걸 대비해 미리 넣어두는 것도 나쁘지 않다.
 *
 * 관리자(Admin) 로그인 추가하면서 필터체인을 두 개로 쪼개는 대신(fm-backend/스펙 문서가 제안한 방식),
 * JwtAuthenticationFilter 하나가 type(MEMBER/ADMIN) 클레임까지 같이 읽어 인증 주체를 만들고,
 * 인가만 아래 requestMatchers에서 role로 나눈다 — 필터체인을 안 쪼갠 게 "설계를 너무 많이 바꾸지
 * 않는다"는 원칙에 더 맞는다고 판단.
 */
@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private final CustomOidcUserService customOidcUserService;
    private final JwtTokenProvider jwtTokenProvider;
    private final AccessTokenBlacklistRepository accessTokenBlacklistRepository;
    private final OAuth2LoginSuccessHandler oAuth2LoginSuccessHandler;
    private final JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;
    private final JwtAccessDeniedHandler jwtAccessDeniedHandler;

    @Bean
    public PasswordEncoder passwordEncoder() {
        // 관리자 비밀번호 해싱용. haeyaji/fm-backend와 동일하게 델리게이팅 인코더(기본 bcrypt) 사용.
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {

        http
                .csrf(csrf -> csrf.disable()) // JWT + httpOnly 쿠키 조합이면 세션 기반 CSRF는 해당 없음.
                                               // 폼 기반 흐름을 섞어 쓴다면 haeyaji처럼 CookieCsrfTokenRepository로 켤 것.

                .cors(Customizer.withDefaults())

                .formLogin(login -> login.disable())
                .httpBasic(basic -> basic.disable())

                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(jwtAuthenticationEntryPoint)
                        .accessDeniedHandler(jwtAccessDeniedHandler)
                )

                .oauth2Login(oauth2 -> oauth2
                        .userInfoEndpoint(userInfo -> userInfo
                                .oidcUserService(customOidcUserService))
                        .successHandler(oAuth2LoginSuccessHandler)
                )

                .addFilterBefore(
                        new JwtAuthenticationFilter(jwtTokenProvider, accessTokenBlacklistRepository),
                        UsernamePasswordAuthenticationFilter.class)

                // 카카오로 리다이렉트되기 전에 ?rememberMe=true를 쿠키에 담아둬야 하므로
                // 실제 리다이렉트를 일으키는 OAuth2AuthorizationRequestRedirectFilter보다 앞에 둔다.
                .addFilterBefore(
                        new RememberMeRequestFilter(),
                        OAuth2AuthorizationRequestRedirectFilter.class)

                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .requestMatchers(
                                "/", "/login/**", "/oauth2/**", "/auth/reissue",
                                "/webhook/kakao/unlink" // 카카오가 호출하는 웹훅 — 인증 쿠키 없이 들어옴
                        ).permitAll()
                        .requestMatchers(HttpMethod.POST, "/admin/login").permitAll()
                        // 계정 발급/삭제는 SUPER_ADMIN 전용 — 일반 ADMIN 매처보다 먼저 와야 함
                        .requestMatchers(HttpMethod.POST, "/admin").hasAuthority("ROLE_SUPER_ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/admin/**").hasAuthority("ROLE_SUPER_ADMIN")
                        .requestMatchers("/admin/**").hasAnyAuthority("ROLE_ADMIN", "ROLE_SUPER_ADMIN")
                        .anyRequest().authenticated())
        ;

        return http.build();
    }
}
