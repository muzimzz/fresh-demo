package com.example.freshdemo.member.service;

import com.example.freshdemo.auth.jwt.AccessTokenValidAfterRepository;
import com.example.freshdemo.auth.jwt.JwtTokenProvider;
import com.example.freshdemo.auth.jwt.RefreshTokenRepository;
import com.example.freshdemo.auth.jwt.TokenType;
import com.example.freshdemo.common.exception.BusinessException;
import com.example.freshdemo.common.exception.ErrorCode;
import com.example.freshdemo.member.domain.Member;
import com.example.freshdemo.member.domain.SocialType;
import com.example.freshdemo.member.repository.MemberRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회원탈퇴 유스케이스. 우리 쪽에서 능동적으로 탈퇴 버튼을 눌렀을 때(카카오 unlink 웹훅이 아니라)의 진입점.
 * 순서가 중요하다:
 *   1) DB 상태 변경(WITHDRAWN) — 이 트랜잭션이 실패하면 아래 아무것도 실행 안 됨
 *   2) refreshToken 삭제 — 재발급을 막음
 *   3) accessTokenValidAfter 커트라인을 지금 시각으로 등록 — 이미 발급된 토큰도 즉시 차단
 *   4) 카카오 unlink 호출은 여기서 직접 안 하고 이벤트로 미룸(KakaoUnlinkEventListener, AFTER_COMMIT) —
 *      DB 트랜잭션이 실제로 커밋된 뒤에만 외부에 "탈퇴했다"고 통보하기 위함
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
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));

        if (member.isWithdrawn()) {
            throw new BusinessException(ErrorCode.MEMBER_ALREADY_WITHDRAWN);
        }

        // TODO(주문 도메인 추가 시): 진행 중 주문/미완료 환불이 있으면 여기서 막아야 한다(Member.withdraw() 참고).
        // 지금은 order 모듈이 없는 인증 데모 범위라 체크 없이 바로 진행한다.

        String kakaoUserId = member.getProviderUserId();

        member.withdraw();

        String role = member.getRole().name();
        refreshTokenRepository.delete(TokenType.MEMBER, role, memberId);
        accessTokenValidAfterRepository.invalidateBefore(
                role, memberId, LocalDateTime.now(), Duration.ofMillis(jwtTokenProvider.getAccessTokenValidityMs()));

        eventPublisher.publishEvent(new MemberWithdrawalEvent(memberId, kakaoUserId));
    }

    /**
     * 카카오 쪽에서 먼저 연결을 끊은 경우(웹훅으로 통보받음) — 우리는 이미 끊긴 연결을 다시 끊을 필요 없이
     * DB 상태만 맞추면 된다. 그래서 카카오 unlink 호출 없이 탈퇴 처리만 한다.
     */
    @Transactional
    public void withdrawByKakaoWebhook(String kakaoUserId) {
        String activeProviderKey = Member.buildActiveProviderKey(SocialType.KAKAO, kakaoUserId);

        // activeProviderKey로 못 찾으면 회원이 없거나 이미 탈퇴한 상태(키가 이미 비워짐)라는 뜻 —
        // 어느 쪽이든 여기서 더 할 일이 없다. 별도 isWithdrawn() 필터가 필요 없어졌다.
        memberRepository.findByActiveProviderKey(activeProviderKey)
                .ifPresent(member -> {
                    member.withdraw();
                    String role = member.getRole().name();
                    refreshTokenRepository.delete(TokenType.MEMBER, role, member.getId());
                    accessTokenValidAfterRepository.invalidateBefore(
                            role, member.getId(), LocalDateTime.now(),
                            Duration.ofMillis(jwtTokenProvider.getAccessTokenValidityMs()));
                });
        // 회원이 없거나 이미 탈퇴 상태여도 예외를 던지지 않는다 — 웹훅 응답은 무조건 200이어야 하므로
        // (카카오 문서: "사용자 정보가 없거나 오류 발생 시에도 200 OK로 응답해야 한다")
    }
}
