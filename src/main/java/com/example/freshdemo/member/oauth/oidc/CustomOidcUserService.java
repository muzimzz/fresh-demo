package com.example.freshdemo.member.oauth.oidc;

import com.example.freshdemo.common.exception.BusinessException;
import com.example.freshdemo.common.exception.ErrorCode;
import com.example.freshdemo.member.domain.Member;
import com.example.freshdemo.member.domain.SocialType;
import com.example.freshdemo.member.oauth.OAuthAttributes;
import com.example.freshdemo.member.repository.MemberRepository;
import com.example.freshdemo.membergrade.domain.MemberGrade;
import com.example.freshdemo.membergrade.repository.MemberGradeRepository;
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
    private final MemberGradeRepository memberGradeRepository;

    @Override
    @Transactional
    public OidcUser loadUser(OidcUserRequest userRequest) throws OAuth2AuthenticationException {
        OidcUser oidcUser = super.loadUser(userRequest); // id_token 검증 완료된 상태

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
            // activeProviderKey로 찾았다는 건 이미 "현재 활성" 상태라는 뜻이다(탈퇴하면 이 키가
            // null로 비워지므로 여기 걸릴 수 없다) — 그래서 탈퇴 여부를 따로 체크할 필요가 없다.
            // 예전엔 여기서 매 로그인마다 카카오 email로 덮어썼는데(update(String)), 이제 email이
            // 온보딩 폼 입력값이라(Member.email 주석 참고) 로그인 시점엔 아무것도 갱신하지 않는다 —
            // "카카오에서는 최초 1회도 안 받아오고, 그 뒤로도 절대 덮어쓰지 않는다"는 원칙 그대로.
            member = optionalMember.get();
        } else {
            // activeProviderKey로 못 찾은 경우 = 완전 신규 가입이거나, 예전에 탈퇴해서 키가 비워진
            // 계정의 재가입이거나 — 어느 쪽이든 "새 행"을 만든다. 탈퇴했던 옛 행은 재활성화하지
            // 않고 이력으로만 남겨둔다(active_provider_key 설계).
            //
            // 신규 행은 memberGradeId가 NOT NULL이라 항상 기본 등급(isDefault=true)을 먼저 찾아
            // 배정한다 — 이 조회가 비어 있으면(운영 실수로 기본 등급 시드가 없는 경우) 가입 자체를
            // 막는 게 맞다고 판단해 예외를 던진다. 등급을 바꾸는 기능은 아직 없어 이후엔 그대로 유지된다.
            try {
                Long defaultGradeId = memberGradeRepository.findByIsDefaultTrue()
                        .map(MemberGrade::getId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.DEFAULT_MEMBER_GRADE_NOT_FOUND));
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
