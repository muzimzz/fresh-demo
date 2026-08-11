package com.example.freshdemo.member.dto;

import com.example.freshdemo.member.domain.Member;
import com.example.freshdemo.member.domain.MemberRole;
import com.example.freshdemo.member.domain.MemberStatus;
import java.time.LocalDateTime;

public record MemberResponse(
        String nickname,
        String email,
        String phone,
        String address,
        MemberRole role,
        MemberStatus status,
        LocalDateTime createdAt
) {
    public static MemberResponse from(Member member) {
        return new MemberResponse(
                member.getNickname(),
                member.getEmail(),
                member.getPhone(),
                member.getAddress(),
                member.getRole(),
                member.getStatus(),
                member.getCreatedAt()
        );
    }
}
