package com.example.freshdemo.address.domain;

import com.example.freshdemo.common.jpa.MutableBaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 회원 배송지. Member와 마찬가지로 @ManyToOne 대신 memberId(UUID)를 직접 들고 있다
 * (fm-backend의 Admin 연관관계 설계와 같은 이유 — 연관관계 매핑 없이 ID만으로 소유권을 확인해도
 * 충분하고, 지연 로딩/N+1 걱정을 줄일 수 있어서).
 *
 * 목표 DDL에는 "기본 배송지 1개만 허용"을 MySQL generated column(active 트릭)으로 강제하는
 * 설계가 있었지만, 여기서는 Hibernate ddl-auto:update로 그 패턴을 깔끔하게 재현하기 어려워
 * 서비스 레이어(AddressService)에서 트랜잭션으로 "기존 기본 배송지 해제 후 새로 지정"하는
 * 방식으로 대신한다.
 */
@Entity
@Getter
@Table(name = "address")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Address extends MutableBaseEntity {

    @Column(name = "member_id", nullable = false)
    private UUID memberId;

    @Column(nullable = false, length = 50)
    private String recipient;

    @Column(nullable = false, length = 20)
    private String phone;

    @Column(nullable = false, length = 10)
    private String zipcode;

    @Column(name = "road_address", nullable = false, length = 255)
    private String roadAddress;

    @Column(name = "detail_address", nullable = false, length = 255)
    private String detailAddress;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault;

    @Builder
    private Address(UUID memberId, String recipient, String phone, String zipcode,
                     String roadAddress, String detailAddress, boolean isDefault) {
        this.memberId = memberId;
        this.recipient = recipient;
        this.phone = phone;
        this.zipcode = zipcode;
        this.roadAddress = roadAddress;
        this.detailAddress = detailAddress;
        this.isDefault = isDefault;
    }

    public void markAsDefault() {
        this.isDefault = true;
    }

    public void unmarkAsDefault() {
        this.isDefault = false;
    }

    public void update(String recipient, String phone, String zipcode, String roadAddress, String detailAddress) {
        this.recipient = recipient;
        this.phone = phone;
        this.zipcode = zipcode;
        this.roadAddress = roadAddress;
        this.detailAddress = detailAddress;
    }
}
