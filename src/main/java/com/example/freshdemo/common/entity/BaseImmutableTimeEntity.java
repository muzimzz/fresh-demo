package com.example.freshdemo.common.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import java.time.LocalDateTime;
import lombok.Getter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * [LG-fm 컨벤션 적용] 생성만 되고 수정되지 않는 엔티티용 베이스(id + createdAt만). fresh-demo v1
 * 도메인(Member/Admin/Address/MemberGrade) 전부 updatedAt이 필요한 mutable 엔티티라 이번
 * 이식에서 실제로 상속하는 곳은 없지만, LG-fm처럼 Immutable/Mutable 2단 구조를 갖춰 앞으로
 * 생성 전용 엔티티(이력/로그성 테이블 등)가 추가될 때 바로 쓸 수 있게 해 둔다.
 */
@Getter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseImmutableTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
