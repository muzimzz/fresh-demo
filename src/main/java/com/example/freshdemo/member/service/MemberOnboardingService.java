package com.example.freshdemo.member.service;

import com.example.freshdemo.common.exception.BusinessException;
import com.example.freshdemo.common.exception.ErrorCode;
import com.example.freshdemo.member.domain.Member;
import com.example.freshdemo.member.repository.MemberRepository;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회원 온보딩(필수 정보 입력) 유스케이스. PENDING_PROFILE 상태의 회원이 최초로 이 API를
 * 호출하면 ACTIVE로 넘어간다 — Member.completeOnboarding() 참고.
 *
 * 약관 동의 여부 자체는 여기서 안 본다 — MemberOnboardingRequest의 @AssertTrue가 이미
 * false를 걸러낸 뒤라, 이 메서드에 도달했다는 것 자체가 "동의함"을 의미한다.
 */
@Service
@RequiredArgsConstructor
public class MemberOnboardingService {

    private final MemberRepository memberRepository;

    @Transactional
    public Member completeOnboarding(UUID memberId, String nickname, boolean marketingAgreed) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));

        if (member.isWithdrawn()) {
            throw new BusinessException(ErrorCode.MEMBER_ALREADY_WITHDRAWN);
        }

        // 이미 그 닉네임을 쓰고 있는 본인이 재호출하는 경우(정보 수정)는 중복으로 안 친다.
        if (!nickname.equals(member.getNickname()) && memberRepository.existsByNickname(nickname)) {
            throw new BusinessException(ErrorCode.DUPLICATE_NICKNAME);
        }

        return member.completeOnboarding(nickname, LocalDateTime.now(), marketingAgreed);
    }
}
