package com.example.freshdemo.member.oauth.oidc;

import com.example.freshdemo.member.domain.Member;
import com.example.freshdemo.member.domain.SocialType;
import com.example.freshdemo.member.oauth.OAuthAttributes;
import com.example.freshdemo.member.repository.MemberRepository;
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
 * 여기서 던지는 OAuth2AuthenticationException은 OAuth2LoginFailureHandler가 최종적으로 잡아서
 * event=MEMBER_LOGIN_FAILED로 로깅한다 — 그 핸들러는 예외 클래스명 정도만 아니까, 여기서는 그보다
 * 구체적인 맥락(어떤 소셜 타입 요청이었는지 등)을 먼저 로그로 남겨두고 던진다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomOidcUserService extends OidcUserService {

    private final MemberRepository memberRepository;

    @Override
    @Transactional
    public OidcUser loadUser(OidcUserRequest userRequest) throws OAuth2AuthenticationException {
        OidcUser oidcUser = super.loadUser(userRequest); // id_token 검증 완료된 상태

        String registrationId = userRequest.getClientRegistration().getRegistrationId();

        SocialType socialType;
        try {
            socialType = SocialType.valueOf(registrationId.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("event=MEMBER_LOGIN_FAILED reason=UNSUPPORTED_REGISTRATION_ID registrationId={}", registrationId);
            throw new OAuth2AuthenticationException("지원하지 않는 소셜 로그인, [registrationId]: " + registrationId);
        }

        String userNameAttributeName = userRequest.getClientRegistration()
                .getProviderDetails().getUserInfoEndpoint().getUserNameAttributeName();

        OAuthAttributes attrs = OAuthAttributes.of(socialType, userNameAttributeName, oidcUser.getAttributes());

        String activeProviderKey = Member.buildActiveProviderKey(attrs.socialType(), attrs.socialTypeId());
        Optional<Member> optionalMember = memberRepository.findByActiveProviderKey(activeProviderKey);

        Member member;
        if (optionalMember.isPresent()) {
            // activeProviderKey로 찾았다는 건 이미 "현재 활성" 상태라는 뜻이다(탈퇴하면 이 키가
            // null로 비워지므로 여기 걸릴 수 없다) — 그래서 탈퇴 여부를 따로 체크할 필요가 없다.
            member = optionalMember.get().update(attrs.email());
        } else {
            // activeProviderKey로 못 찾은 경우 = 완전 신규 가입이거나, 예전에 탈퇴해서 키가 비워진
            // 계정의 재가입이거나 — 어느 쪽이든 "새 행"을 만든다. 탈퇴했던 옛 행은 재활성화하지
            // 않고 이력으로만 남겨둔다(active_provider_key 설계).
            try {
                member = memberRepository.saveAndFlush(attrs.toEntity());
            } catch (DataIntegrityViolationException e) {
                member = memberRepository.findByActiveProviderKey(activeProviderKey)
                        .orElseThrow(() -> {
                            log.warn("event=MEMBER_LOGIN_FAILED reason=SIGNUP_RACE_UNRESOLVED socialType={}", socialType);
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
