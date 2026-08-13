package com.example.freshdemo.address.dto;

import com.example.freshdemo.address.domain.Address;
import java.time.LocalDateTime;
import lombok.Builder;

@Builder
public record AddressResponse(
        Long id,
        String recipient,
        String phone,
        String zipcode,
        String roadAddress,
        String detailAddress,
        boolean isDefault,
        LocalDateTime createdAt
) {

    public static AddressResponse from(Address address) {
        return AddressResponse.builder()
                .id(address.getId())
                .recipient(address.getRecipient())
                .phone(address.getPhone())
                .zipcode(address.getZipcode())
                .roadAddress(address.getRoadAddress())
                .detailAddress(address.getDetailAddress())
                .isDefault(address.isDefault())
                .createdAt(address.getCreatedAt())
                .build();
    }
}
