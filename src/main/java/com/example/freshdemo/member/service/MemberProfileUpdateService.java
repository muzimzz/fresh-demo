package com.example.freshdemo.member.service;

import com.example.freshdemo.common.exception.BusinessException;
import com.example.freshdemo.common.exception.ErrorCode;
import com.example.freshdemo.member.domain.Member;
import com.example.freshdemo.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 요구사항의 "회원 정보 관리"(하) 유스케이스. 이름은 원래 MemberProfileService였는데, 이미 있는
 * MemberOnboardingService와 이름이 너무 비슷해서(둘 다 "회원 프로필을 다루는 서비스"로 읽힘)
 * MemberProfileUpdateService로 바꿨다.
 *
 * 온보딩과 합치지 않고 별도 서비스로 남긴 이유: 온보딩은 PENDING_PROFILE→ACTIVE 상태 전이와
 * 약관동의를 함께 처리하는 "가입 완료" 유스케이스이고, 이건 이미 ACTIVE인 회원이 프로필을 고치는
 * "일반 수정" 유스케이스라 책임이 다르다(상태 전이 없음, 약관동의 재확인 없음). 필드가 일부
 * 겹친다고(name/nickname) 서비스까지 합치면, 온보딩 전용 로직(상태 전이)과 일반 수정 전용 로직이
 * 한 클래스에 섞여 오히려 언제 어떤 분기를 타는지 읽기 어려워진다고 판단했다.
 */
@Service
@RequiredArgsConstructor
public class MemberProfileUpdateService {

    private final MemberRepository memberRepository;

    @Transactional
    public Member updateProfile(Long memberId, String name, String email, String nickname, String phone, String address) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));

        if (member.isWithdrawn()) {
            throw new BusinessException(ErrorCode.MEMBER_ALREADY_WITHDRAWN);
        }

        // 본인이 원래 쓰던 닉네임 그대로 재제출하는 경우(변경 없음)는 중복으로 안 친다 —
        // MemberOnboardingService.completeOnboarding()과 동일한 패턴.
        if (!nickname.equals(member.getNickname()) && memberRepository.existsByNickname(nickname)) {
            throw new BusinessException(ErrorCode.DUPLICATE_NICKNAME);
        }

        return member.updateProfile(name, nickname, email, phone, address);
    }
}
