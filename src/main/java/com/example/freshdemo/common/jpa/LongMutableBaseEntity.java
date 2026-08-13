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
 * 모든 엔티티가 공유하는 Long(AUTO_INCREMENT) PK 베이스.
 *
 * 원래는 Member/Admin/Address 등은 UUID(v7) PK(UuidBaseEntity 계열)를 쓰고, member_grade처럼
 * 외부에 노출 안 되는 내부 참조 테이블만 이 클래스(Long PK)를 쓰는 절충안이었다 — 이후 PK 전략을
 * 전면적으로 Long으로 통일하기로 하면서 UuidBaseEntity/ImmutableBaseEntity/MutableBaseEntity를
 * 전부 삭제하고 이 클래스 하나로 합쳤다(DESIGN_NOTES.md 9번 참고).
 *
 * id를 그대로 JWT sub/URL 경로/응답 body에 노출한다 — 순차 증가값이라 계정 수 등을 추측할 수 있는
 * enumeration 여지가 생기지만(예: adminId=15가 존재하면 14/16도 있을 거라는 게 드러남), 지금
 * 프로젝트 규모에서는 그 리스크보다 단순함을 우선하기로 했다. 나중에 필요해지면 이 Long PK는
 * 내부용으로만 남기고 별도 외부노출용 식별자(public_id)를 추가하는 방향으로 갈 수 있다.
 *
 * "생성만 되고 안 바뀌는 것"과 "수정도 되는 것"을 구분할 필요가 생기면 Immutable/Mutable 2단
 * 구조로 다시 쪼갤 수 있다 — 지금은 모든 엔티티가 updatedAt도 같이 써서 분리할 실익이 없다.
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
