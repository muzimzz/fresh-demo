package com.example.freshdemo.address.controller;

import com.example.freshdemo.address.domain.Address;
import com.example.freshdemo.address.dto.AddressRequest;
import com.example.freshdemo.address.dto.AddressResponse;
import com.example.freshdemo.address.service.AddressService;
import com.example.freshdemo.auth.CustomUserDetails;
import com.example.freshdemo.common.response.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 회원 배송지 API. 실제 경로 /api/addresses/**. */
@RestController
@RequestMapping("/addresses")
@RequiredArgsConstructor
public class AddressController {

    private final AddressService addressService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<AddressResponse>>> findMyAddresses(
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        List<AddressResponse> responses = addressService.findMyAddresses(userDetails.getId()).stream()
                .map(AddressResponse::from)
                .toList();
        return ResponseEntity.ok(ApiResponse.of(responses));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<AddressResponse>> create(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestBody @Valid AddressRequest request
    ) {
        Address address = addressService.create(userDetails.getId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(AddressResponse.from(address)));
    }

    @PutMapping("/{addressId}")
    public ResponseEntity<ApiResponse<AddressResponse>> update(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID addressId,
            @RequestBody @Valid AddressRequest request
    ) {
        Address address = addressService.update(userDetails.getId(), addressId, request);
        return ResponseEntity.ok(ApiResponse.of(AddressResponse.from(address)));
    }

    @DeleteMapping("/{addressId}")
    public ResponseEntity<ApiResponse<Void>> delete(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable UUID addressId
    ) {
        addressService.delete(userDetails.getId(), addressId);
        return ResponseEntity.ok(ApiResponse.of(null));
    }
}
