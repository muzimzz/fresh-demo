package com.example.freshdemo.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 관리자 본인의 비밀번호 변경 요청(임시 비밀번호로 로그인한 뒤 바꿀 때도 이 API를 그대로 쓴다 —
 * "강제" 게이트는 없지만 "바꿀 방법"은 있어야 하므로). currentPassword 확인 없이는 세션을 탈취한
 * 공격자도 비밀번호를 바꿔서 원래 주인을 완전히 몰아낼 수 있어 반드시 같이 받는다.
 */
public record AdminPasswordChangeRequest(
        @NotBlank String currentPassword,
        @NotBlank @Size(min = 8, max = 100) String newPassword
) {
}
