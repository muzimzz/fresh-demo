package com.example.freshdemo.address.dto;

import com.example.freshdemo.address.domain.Address;
import jakarta.validation.constraints.NotBlank;

public record AddressRequest(
        @NotBlank String recipient,
        @NotBlank String phone,
        @NotBlank String zipcode,
        @NotBlank String roadAddress,
        String detailAddress,
        boolean isDefault
) {

    public Address toEntity(Long memberId) {
        return Address.builder()
                .memberId(memberId)
                .recipient(this.recipient)
                .phone(this.phone)
                .zipcode(this.zipcode)
                .roadAddress(this.roadAddress)
                .detailAddress(this.detailAddress)
                .isDefault(this.isDefault)
                .build();
    }
}
