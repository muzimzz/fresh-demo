package com.example.freshdemo.member.domain.service;

import com.example.freshdemo.common.auth.jwt.AccessTokenValidAfterRepository;
import com.example.freshdemo.common.auth.jwt.JwtTokenProvider;
import com.example.freshdemo.common.auth.jwt.RefreshTokenRepository;
import com.example.freshdemo.common.auth.jwt.TokenType;
import com.example.freshdemo.member.domain.entity.Member;
import com.example.freshdemo.member.domain.entity.SocialType;
import com.example.freshdemo.member.domain.repository.MemberRepository;
import com.example.freshdemo.member.exception.MemberErrorCode;
import com.example.freshdemo.member.exception.MemberException;
import java.time.Duration;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회원탈퇴 유스케이스. 순서: 1) DB 상태 변경(WITHDRAWN) 2) refreshToken 삭제 3) accessTokenValidAfter
 * 커트라인 등록 4) 카카오 unlink는 AFTER_COMMIT 이벤트로 미룸(KakaoUnlinkEventListener).
 *
 * [LG-fm 컨벤션 리팩토링] member.domain.service로 이동, auth.jwt -> common.auth.jwt(공용 인증
 * 인프라 재배치), 예외 타입만 변경. 로직 무변경.
 */
@Service
@RequiredArgsConstructor
public class MemberWithdrawalService {

    private final MemberRepository memberRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final AccessTokenValidAfterRepository accessTokenValidAfterRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public void withdraw(Long memberId) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new MemberException(MemberErrorCode.MEMBER_NOT_FOUND));

        if (member.isWithdrawn()) {
            throw new MemberException(MemberErrorCode.MEMBER_ALREADY_WITHDRAWN);
        }

        // TODO(주문 도메인 추가 시): 진행 중 주문/미완료 환불이 있으면 여기서 막아야 한다.

        String kakaoUserId = member.getProviderUserId();

        member.withdraw();

        String role = member.getRole().name();
        refreshTokenRepository.delete(TokenType.MEMBER, role, memberId);
        accessTokenValidAfterRepository.invalidateBefore(
                role, memberId, LocalDateTime.now(), Duration.ofMillis(jwtTokenProvider.getAccessTokenValidityMs()));

        eventPublisher.publishEvent(new MemberWithdrawalEvent(memberId, kakaoUserId));
    }

    /** 카카오 쪽에서 먼저 연결을 끊은 경우(웹훅으로 통보) — DB 상태만 맞추고 unlink는 다시 호출하지 않는다. */
    @Transactional
    public void withdrawByKakaoWebhook(String kakaoUserId) {
        String activeProviderKey = Member.buildActiveProviderKey(SocialType.KAKAO, kakaoUserId);

        memberRepository.findByActiveProviderKey(activeProviderKey)
                .ifPresent(member -> {
                    member.withdraw();
                    String role = member.getRole().name();
                    refreshTokenRepository.delete(TokenType.MEMBER, role, member.getId());
                    accessTokenValidAfterRepository.invalidateBefore(
                            role, member.getId(), LocalDateTime.now(),
                            Duration.ofMillis(jwtTokenProvider.getAccessTokenValidityMs()));
                });
        // 회원이 없거나 이미 탈퇴 상태여도 예외를 던지지 않는다 — 웹훅 응답은 무조건 200이어야 한다.
    }
}
