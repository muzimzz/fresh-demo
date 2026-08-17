package com.example.freshdemo.config;

import com.example.freshdemo.common.auth.jwt.AccessTokenValidAfterRepository;
import com.example.freshdemo.common.auth.jwt.JwtAuthenticationFilter;
import com.example.freshdemo.common.auth.jwt.JwtTokenProvider;
import com.example.freshdemo.common.auth.jwt.RememberMeRequestFilter;
import com.example.freshdemo.member.MemberOAuth2LoginConfigurer;
import jakarta.servlet.DispatcherType;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
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
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * 카카오만 지원하는 버전의 SecurityConfig — 다른 소셜 로그인(naver/google 등) 관련 설정은 없다.
 * ASYNC/ERROR dispatcher permitAll 부분은 이 프로젝트에서 겪었던 SSE 재디스패치 403/500 버그의
 * 해결책이라 그대로 가져왔다 — SSE(또는 비슷한 비동기 스트리밍 응답)를 쓸 계획이 없다면 없어도 무방하지만,
 * 나중에 추가할 걸 대비해 미리 넣어두는 것도 나쁘지 않다.
 *
 * 관리자(Admin) 로그인 추가하면서 필터체인을 두 개로 쪼개는 대신(fm-backend/스펙 문서가 제안한 방식),
 * JwtAuthenticationFilter 하나가 type(MEMBER/ADMIN) 클레임까지 같이 읽어 인증 주체를 만들고,
 * 인가만 아래 requestMatchers에서 role로 나눈다 — 필터체인을 안 쪼갠 게 "설계를 너무 많이 바꾸지
 * 않는다"는 원칙에 더 맞는다고 판단.
 *
 * [LG-fm 컨벤션 리팩토링] 기존엔 JwtAuthenticationEntryPoint/JwtAccessDeniedHandler가 필터
 * 예외를 직접 잡아 ApiResponse JSON을 작성했다. LG-fm 컨벤션은 "오류 응답 구조는
 * GlobalExceptionHandler가 혼자 소유한다"는 원칙 아래, 필터 단계의 인증/인가 예외를
 * HandlerExceptionResolver로 다시 MVC 예외 처리(GlobalExceptionHandler)에 위임한다 — 그래서 그
 * 두 클래스는 삭제하고 이 방식으로 바꿨다(GlobalExceptionHandler의 AuthenticationException/
 * AccessDeniedException 핸들러가 대신 응답을 만든다).
 *
 * [LG-fm 컨벤션 리팩토링 2차] CustomOidcUserService/OAuth2LoginSuccessHandler/
 * OAuth2LoginFailureHandler(전부 member.domain.oauth 소속)를 여기서 직접 필드로 주입받던 것을
 * member.MemberOAuth2LoginConfigurer 하나로 대체했다 — config가 도메인 내부(domain 하위)를
 * 직접 알면 안 된다는 원칙(common_은_도메인을_모른다) 때문이다. 실제 OAuth2 로그인 처리 로직은
 * 전혀 안 바꿨고, "누가 그 셋을 필터체인에 조립해 꽂느냐"만 member.domain 안의
 * MemberOAuth2LoginConfigurerImpl로 옮겼다.
 */
@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtTokenProvider jwtTokenProvider;
    private final AccessTokenValidAfterRepository accessTokenValidAfterRepository;
    private final MemberOAuth2LoginConfigurer memberOAuth2LoginConfigurer;

    @Qualifier("handlerExceptionResolver")
    private final HandlerExceptionResolver handlerExceptionResolver;

    @Bean
    public PasswordEncoder passwordEncoder() {
        // 관리자 비밀번호 해싱용. fm-backend와 동일하게 델리게이팅 인코더(기본 bcrypt) 사용.
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {

        http
                .csrf(csrf -> csrf.disable()) // JWT + httpOnly 쿠키 조합이면 세션 기반 CSRF는 해당 없음.
                                               // 폼 기반 흐름을 섞어 쓴다면 CookieCsrfTokenRepository로 켤 것.

                .cors(Customizer.withDefaults())

                .formLogin(login -> login.disable())
                .httpBasic(basic -> basic.disable())

                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint((request, response, authException) ->
                                handlerExceptionResolver.resolveException(request, response, null, authException))
                        .accessDeniedHandler((request, response, accessDeniedException) ->
                                handlerExceptionResolver.resolveException(request, response, null, accessDeniedException))
                )

                .oauth2Login(memberOAuth2LoginConfigurer::configure)

                .addFilterBefore(
                        new JwtAuthenticationFilter(jwtTokenProvider, accessTokenValidAfterRepository),
                        UsernamePasswordAuthenticationFilter.class)

                // 카카오로 리다이렉트되기 전에 ?rememberMe=true를 쿠키에 담아둬야 하므로
                // 실제 리다이렉트를 일으키는 OAuth2AuthorizationRequestRedirectFilter보다 앞에 둔다.
                .addFilterBefore(
                        new RememberMeRequestFilter(),
                        OAuth2AuthorizationRequestRedirectFilter.class)

                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .requestMatchers(
                                "/", "/login/**", "/oauth2/**",
                                "/webhook/kakao/unlink" // 카카오가 호출하는 웹훅 — 인증 쿠키 없이 들어옴
                        ).permitAll()
                        // [LG-fm 컨벤션 리팩토링 3차] common.auth.AuthController 하나가 갖고 있던
                        // "/auth/reissue"가 회원/관리자용 컨트롤러로 쪼개지면서 경로도 나뉘었다.
                        // access token이 만료된 상태로 오는 요청이라 인증 없이 permitAll이어야 한다.
                        .requestMatchers(HttpMethod.POST, "/members/reissue", "/admin/reissue", "/admin/login").permitAll()
                        // 계정 발급/삭제는 SUPER_ADMIN 전용 — 일반 ADMIN 매처보다 먼저 와야 함
                        .requestMatchers(HttpMethod.POST, "/admin").hasAuthority("ROLE_SUPER_ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/admin/**").hasAuthority("ROLE_SUPER_ADMIN")
                        .requestMatchers("/admin/**").hasAnyAuthority("ROLE_ADMIN", "ROLE_SUPER_ADMIN")
                        // 회원 전용 API — 관리자 토큰이 authenticated() 하나로만 막혀 있으면 그냥 통과돼버림
                        // (예: 관리자 토큰으로 POST /addresses를 호출하면 존재 확인 없이 admin id를
                        // memberId로 하는 배송지가 생성됨). role이 아니라 TYPE_MEMBER로 막아서, 회원
                        // role 종류가 나중에 늘어나도 이 규칙은 안 건드려도 되게 한다.
                        .requestMatchers("/addresses/**", "/members/**").hasAuthority("TYPE_MEMBER")
                        .anyRequest().authenticated())
        ;

        return http.build();
    }
}
