package com.example.freshdemo.address.repository;

import com.example.freshdemo.address.domain.Address;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface AddressRepository extends JpaRepository<Address, Long> {

    List<Address> findByMemberIdOrderByCreatedAtDesc(Long memberId);

    Optional<Address> findByIdAndMemberId(Long id, Long memberId);

    long countByMemberId(Long memberId);

    /** 새 기본 배송지를 지정하기 전, 기존에 기본이던 배송지를 먼저 해제한다. */
    @Modifying
    @Query("update Address a set a.isDefault = false where a.memberId = :memberId and a.isDefault = true")
    void clearDefaultForMember(Long memberId);
}
