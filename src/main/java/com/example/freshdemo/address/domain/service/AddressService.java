package com.example.freshdemo.address.domain.service;

import com.example.freshdemo.address.domain.entity.Address;
import com.example.freshdemo.address.domain.repository.AddressRepository;
import com.example.freshdemo.address.dto.AddressRequest;
import com.example.freshdemo.address.exception.AddressErrorCode;
import com.example.freshdemo.address.exception.AddressException;
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
        boolean isFirstAddress = addressRepository.countByMemberId(memberId) == 0;
        boolean shouldBeDefault = isFirstAddress || request.isDefault();

        if (shouldBeDefault) {
            addressRepository.clearDefaultForMember(memberId);
        }

        Address address = Address.register(
                memberId, request.recipient(), request.phone(), request.zipcode(),
                request.roadAddress(), request.detailAddress(), shouldBeDefault);

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
            addressRepository.findByMemberIdOrderByCreatedAtDesc(memberId).stream()
                    .findFirst()
                    .ifPresent(Address::markAsDefault);
        }
    }

    private Address getOwned(Long memberId, Long addressId) {
        return addressRepository.findByIdAndMemberId(addressId, memberId)
                .orElseThrow(() -> new AddressException(AddressErrorCode.ADDRESS_NOT_FOUND));
    }
}
