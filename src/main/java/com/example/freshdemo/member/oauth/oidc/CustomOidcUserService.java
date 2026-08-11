package com.example.freshdemo.member.oauth.oidc;

import com.example.freshdemo.member.domain.Member;
import com.example.freshdemo.member.domain.SocialType;
import com.example.freshdemo.member.oauth.OAuthAttributes;
import com.example.freshdemo.member.repository.MemberRepository;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
                        .orElseThrow(() -> e);
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
