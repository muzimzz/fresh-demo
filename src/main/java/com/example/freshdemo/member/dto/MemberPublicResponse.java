package com.example.freshdemo.member.dto;

import com.example.freshdemo.common.logging.PiiMasker;
import com.example.freshdemo.member.domain.Member;
import java.util.UUID;

public record MemberPublicResponse(
        UUID id,
        String nickname,
        String maskedEmail
) {
    public static MemberPublicResponse from(Member member) {
        return new MemberPublicResponse(
                member.getId(),
                member.getNickname(),
                PiiMasker.maskEmail(member.getEmail())
        );
    }
}
