package com.example.freshdemo.address.dto;

import jakarta.validation.constraints.NotBlank;

// [LG-fm 컨벤션 리팩토링] toEntity()는 AddressService.create()로 옮겼다(Address.register()
// 정적 팩토리가 memberId 등 여러 인자를 받아 서비스가 조립하는 편이 자연스러워졌다).
public record AddressRequest(
        @NotBlank String recipient,
        @NotBlank String phone,
        @NotBlank String zipcode,
        @NotBlank String roadAddress,
        String detailAddress,
        boolean isDefault
) {
}
