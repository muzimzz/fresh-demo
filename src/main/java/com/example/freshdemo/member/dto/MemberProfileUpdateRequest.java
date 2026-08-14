package com.example.freshdemo.member.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 요구사항의 "회원 정보 관리"(이름, 닉네임, 이메일, 휴대폰, 주소 변경) API 요청 — 다섯 항목 전부 받는다.
 *
 * email이 처음엔 빠져 있었다 — 카카오 로그인마다 이메일을 카카오 값으로 무조건 덮어쓰는 구조였어서
 * (CustomOidcUserService), 여기서 바꿔도 다음 로그인 때 되돌아갔기 때문. 이후 email 자체를 카카오에서
 * 안 받고 온보딩 폼 입력으로 바꾸기로 결정하면서(Member.email 참고) 그 제약이 없어져 다시 넣었다.
 *
 * name/nickname/email은 프로필 전체를 다시 제출하는 폼을 가정해 필수로 받는다. phone/address는
 * 원래도 선택 항목이라(온보딩 필수 항목이 아님) null 허용 — null이면 그 필드는 이번 요청에서
 * 안 건드리고, 빈 문자열이면 명시적으로 지운다(Member.updateProfile 참고).
 */
public record MemberProfileUpdateRequest(
        @NotBlank @Size(max = 50) String name,
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Size(max = 50) String nickname, // Member.nickname 목표 DDL 길이(VARCHAR(50))에 맞춤
        @Size(max = 20) String phone,
        @Size(max = 255) String address
) {
}
