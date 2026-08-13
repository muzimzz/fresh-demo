package com.example.freshdemo.address.service;

import com.example.freshdemo.address.domain.Address;
import com.example.freshdemo.address.dto.AddressRequest;
import com.example.freshdemo.address.repository.AddressRepository;
import com.example.freshdemo.common.exception.BusinessException;
import com.example.freshdemo.common.exception.ErrorCode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AddressService {

    private final AddressRepository addressRepository;

    @Transactional(readOnly = true)
    public List<Address> findMyAddresses(Long memberId) {
        return addressRepository.findByMemberIdOrderByCreatedAtDesc(memberId);
    }

    @Transactional
    public Address create(Long memberId, AddressRequest request) {
        // 첫 배송지는 무조건 기본으로 — 사용자가 매번 기본 여부를 신경 쓰지 않아도 되게.
        boolean isFirstAddress = addressRepository.countByMemberId(memberId) == 0;
        boolean shouldBeDefault = isFirstAddress || request.isDefault();

        if (shouldBeDefault) {
            addressRepository.clearDefaultForMember(memberId);
        }

        Address address = Address.builder()
                .memberId(memberId)
                .recipient(request.recipient())
                .phone(request.phone())
                .zipcode(request.zipcode())
                .roadAddress(request.roadAddress())
                .detailAddress(request.detailAddress())
                .isDefault(shouldBeDefault)
                .build();

        return addressRepository.save(address);
    }

    @Transactional
    public Address update(Long memberId, Long addressId, AddressRequest request) {
        Address address = getOwned(memberId, addressId);

        address.update(request.recipient(), request.phone(), request.zipcode(),
                request.roadAddress(), request.detailAddress());

        if (request.isDefault() && !address.isDefault()) {
            addressRepository.clearDefaultForMember(memberId);
            address.markAsDefault();
        }

        return address;
    }

    @Transactional
    public void delete(Long memberId, Long addressId) {
        Address address = getOwned(memberId, addressId);
        boolean wasDefault = address.isDefault();

        addressRepository.delete(address);

        if (wasDefault) {
            // 기본 배송지가 삭제됐으면 남은 것 중 아무거나(가장 최근 것) 하나를 새 기본으로 승격한다 —
            // 기본 배송지가 하나도 없는 상태로 방치하지 않기 위함.
            addressRepository.findByMemberIdOrderByCreatedAtDesc(memberId).stream()
                    .findFirst()
                    .ifPresent(Address::markAsDefault);
        }
    }

    private Address getOwned(Long memberId, Long addressId) {
        return addressRepository.findByIdAndMemberId(addressId, memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ADDRESS_NOT_FOUND));
    }
}
