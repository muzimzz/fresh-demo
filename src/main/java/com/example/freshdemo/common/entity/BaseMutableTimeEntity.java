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
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * [LG-fm 컨벤션 적용] 기존 common.jpa.LongMutableBaseEntity를 대체한다. 필드 구성(id/createdAt/
 * updatedAt)은 완전히 동일하다 — Member/Admin/Address/MemberGrade 전부 이 클래스를 상속하도록
 * 바뀐다. id를 그대로 노출하는 트레이드오프에 대한 논의는 기존 LongMutableBaseEntity의 주석과
 * DESIGN_NOTES.md 9번을 참고(그 판단 자체는 이번 리팩토링으로 바뀌지 않았다).
 */
@Getter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseMutableTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
