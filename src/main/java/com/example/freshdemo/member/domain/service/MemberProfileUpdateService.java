package com.example.freshdemo.member.domain.service;

import com.example.freshdemo.member.domain.entity.Member;
import com.example.freshdemo.member.domain.repository.MemberRepository;
import com.example.freshdemo.member.exception.MemberErrorCode;
import com.example.freshdemo.member.exception.MemberException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MemberProfileUpdateService {

    private final MemberRepository memberRepository;

    @Transactional
    public Member updateProfile(Long memberId, String name, String email, String nickname, String phone, String address) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new MemberException(MemberErrorCode.MEMBER_NOT_FOUND));

        if (member.isWithdrawn()) {
            throw new MemberException(MemberErrorCode.MEMBER_ALREADY_WITHDRAWN);
        }

        if (!nickname.equals(member.getNickname()) && memberRepository.existsByNickname(nickname)) {
            throw new MemberException(MemberErrorCode.DUPLICATE_NICKNAME);
        }

        return member.updateProfile(name, nickname, email, phone, address);
    }
}
