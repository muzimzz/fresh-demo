package com.example.freshdemo.member.domain.oauth;

import com.example.freshdemo.member.domain.entity.Member;
import com.example.freshdemo.member.domain.entity.SocialType;
import com.example.freshdemo.member.domain.repository.MemberRepository;
import com.example.freshdemo.member.exception.MemberErrorCode;
import com.example.freshdemo.member.exception.MemberException;
import com.example.freshdemo.membergrade.MemberGradeApi;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * [LG-fm 컨벤션 리팩토링] member.domain.oauth로 이동, 예외 타입만 변경. 로직 무변경.
 * 이 클래스는 인증 어댑터라 domain.service보다 domain.oauth가 더 맞는다고 판단해 별도 하위
 * 패키지로 뒀다 — LG-fm 빌드 게이트를 그대로 가져온다면 커버리지 측정 대상(*.domain.service.*)에서
 * 빠진다는 뜻이라 향후 논의 필요.
 *
 * [LG-fm 컨벤션 리팩토링 2차] 기본 등급 조회를 membergrade.domain.repository.MemberGradeRepository
 * 직접 참조에서 membergrade.MemberGradeApi 경유로 바꿨다 — 도메인 내부(domain 하위)는 다른
 * 도메인에 닫혀 있어야 한다는 규칙(ArchUnit 도메인_내부는_다른_도메인에_닫혀_있다) 때문이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomOidcUserService extends OidcUserService {

    private final MemberRepository memberRepository;
    private final MemberGradeApi memberGradeApi;

    @Override
    @Transactional
    public OidcUser loadUser(OidcUserRequest userRequest) throws OAuth2AuthenticationException {
        OidcUser oidcUser = super.loadUser(userRequest);

        String registrationId = userRequest.getClientRegistration().getRegistrationId();

        SocialType provider;
        try {
            provider = SocialType.valueOf(registrationId.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("event=MEMBER_LOGIN_FAILED reason=UNSUPPORTED_REGISTRATION_ID registrationId={}", registrationId);
            throw new OAuth2AuthenticationException("지원하지 않는 소셜 로그인, [registrationId]: " + registrationId);
        }

        String userNameAttributeName = userRequest.getClientRegistration()
                .getProviderDetails().getUserInfoEndpoint().getUserNameAttributeName();

        OAuthAttributes attrs = OAuthAttributes.of(provider, userNameAttributeName, oidcUser.getAttributes());

        String activeProviderKey = Member.buildActiveProviderKey(attrs.provider(), attrs.providerUserId());
        Optional<Member> optionalMember = memberRepository.findByActiveProviderKey(activeProviderKey);

        Member member;
        if (optionalMember.isPresent()) {
            member = optionalMember.get();
        } else {
            try {
                Long defaultGradeId = memberGradeApi.findDefaultGradeId()
                        .orElseThrow(() -> new MemberException(MemberErrorCode.DEFAULT_MEMBER_GRADE_NOT_FOUND));
                member = memberRepository.saveAndFlush(attrs.toEntity(defaultGradeId));
            } catch (DataIntegrityViolationException e) {
                member = memberRepository.findByActiveProviderKey(activeProviderKey)
                        .orElseThrow(() -> {
                            log.warn("event=MEMBER_LOGIN_FAILED reason=SIGNUP_RACE_UNRESOLVED provider={}", provider);
                            return e;
                        });
            }
        }

        return new CustomOidcUser(
                member,
                List.of(new SimpleGrantedAuthority(member.getRole().name())),
                oidcUser.getIdToken(),
                oidcUser.getUserInfo(),
                attrs.nameAttributeKey()
        );
    }
}
