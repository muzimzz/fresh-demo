package com.example.freshdemo.address.domain;

import com.example.freshdemo.common.jpa.LongMutableBaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 회원 배송지. Member와 마찬가지로 @ManyToOne 대신 memberId(Long)를 직접 들고 있다
 * (fm-backend의 Admin 연관관계 설계와 같은 이유 — 연관관계 매핑 없이 ID만으로 소유권을 확인해도
 * 충분하고, 지연 로딩/N+1 걱정을 줄일 수 있어서).
 *
 * "기본 배송지 1개만 허용"은 목표 DDL과 같은 방식(MySQL generated column + UNIQUE)으로 DB
 * 레벨에서도 강제한다 — isDefaultKey 참고. 다만 이게 서비스 레이어(AddressService)의
 * "기존 기본 배송지 해제 후 새로 지정" 로직을 대체하는 건 아니다 — 어느 행을 새 기본으로 할지
 * 고르는 건 여전히 앱이 해야 하고, 이 제약은 그 로직에 버그가 있어도 DB가 최종적으로 막아주는
 * 안전망이다.
 *
 * 주의: 이 프로젝트는 Flyway 없이 ddl-auto:update로 스키마를 관리한다. GENERATED ALWAYS AS
 * 컬럼을 기존 테이블에 새로 추가하는 ALTER TABLE을 Hibernate가 정확히 만들어내는지는 검증이
 * 필요하다 — 로컬에서 안 먹으면(컬럼이 안 생기거나 UNIQUE가 안 걸리면) address 테이블을
 * 드롭하고 재기동하거나, 수동으로 ALTER TABLE을 한 번 실행해야 한다.
 */
@Entity
@Getter
@Table(name = "address")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Address extends LongMutableBaseEntity {

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(nullable = false, length = 50)
    private String recipient;

    @Column(nullable = false, length = 20)
    private String phone;

    @Column(nullable = false, length = 10)
    private String zipcode;

    @Column(name = "road_address", nullable = false, length = 255)
    private String roadAddress;

    // 목표 DDL은 NULL 허용(선택 항목)이다 — AddressRequest.detailAddress도 검증 애너테이션 없이
    // 선택으로 취급하는데, 예전엔 이 컬럼이 nullable=false라 상세주소 없이 등록하면 NOT NULL 위반이
    // 날 수 있는 버그였다.
    @Column(name = "detail_address", length = 255)
    private String detailAddress;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault;

    /**
     * 회원별 기본 배송지 1개 강제용 계산 컬럼. is_default=true인 행만 member_id 값을 갖고,
     * 나머지는 NULL이 된다 — MySQL UNIQUE는 NULL을 여러 개 허용하므로 "기본이 아닌 행끼리"는
     * 검사에서 빠지고, "같은 회원의 기본 배송지가 2개"일 때만 uk_address_single_default_per_member가
     * 걸린다(목표 DDL 3장의 조건부 유일성 기법과 동일). 앱이 직접 값을 넣거나 바꾸지 않는다
     * (insertable/updatable=false) — MySQL이 매번 다시 계산한다.
     */
    @Column(name = "is_default_key", insertable = false, updatable = false, unique = true,
            columnDefinition = "BIGINT GENERATED ALWAYS AS (CASE WHEN is_default THEN member_id ELSE NULL END) STORED")
    private Long isDefaultKey;

    @Builder
    private Address(Long memberId, String recipient, String phone, String zipcode,
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

    /** recipient/phone/zipcode/roadAddress/detailAddress 전부 개인정보라 통째로 뺀다 — id/소유자/기본여부만 남김. */
    @Override
    public String toString() {
        return "Address{id=%s, memberId=%s, isDefault=%s}".formatted(getId(), memberId, isDefault);
    }
}
