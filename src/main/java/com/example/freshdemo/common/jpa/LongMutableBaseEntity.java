package com.example.freshdemo.common.jpa;

import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * UuidBaseEntity 계열(Member/Admin/Address 등, PK=UUID v7)과는 별도로 두는 Long PK 전용 베이스.
 *
 * member_grade처럼 외부에 노출되지 않고 내부 조회/FK용으로만 쓰는 작은 참조(lookup) 테이블은
 * UUID보다 단순 auto-increment PK가 더 자연스럽다(순서 비교가 되고 인덱스도 더 작다) — fm-backend의
 * BaseEntity(Long, GenerationType.IDENTITY) 컨벤션과도 맞춘 선택.
 *
 * UuidBaseEntity 계열처럼 Immutable/Mutable을 따로 분리하지 않고 createdAt/updatedAt을 한
 * 클래스에 합쳐뒀다 — 지금은 Long PK 엔티티가 member_grade 하나뿐이라 분리할 실익이 없어서다.
 * 나중에 Long PK 엔티티가 늘어나고 "생성만 되고 안 바뀌는 것"과 "수정도 되는 것"을 구분해야
 * 할 필요가 생기면 그때 UuidBaseEntity 계열과 같은 3단 구조(Base/Immutable/Mutable)로 쪼개면 된다.
 */
@Getter
@MappedSuperclass
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class LongMutableBaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @CreatedDate
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;
}
