package com.example.freshdemo.common.audit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * 관리자 액션 감사 로그(V1__init_schema.sql의 audit_log 테이블을 그대로 반영). 상품/주문 등 다른
 * 도메인의 관리자 액션(PRODUCT_DELETE/REFUND/GRADE_CHANGE 등, DDL 코멘트 참고)도 같이 쌓는 공용
 * 테이블이라 admin 패키지가 아니라 common 아래에 둔다 — 이번 회원/관리자 리팩토링에서는 관리자
 * 등록/삭제(AdminService)만 이 테이블에 쓴다. 다른 도메인 팀원이 자기 도메인의 관리자 액션을 감사
 * 로그로 남기고 싶으면 이 엔티티/레포지토리를 그대로 재사용하면 된다.
 *
 * action/target/detail의 의미(누가 무엇을 했는지)는 콘솔/JSON 구조화 로그(event=ADMIN_REGISTERED 등,
 * DESIGN_NOTES.md 5번 참고)와 겹치지만 역할이 다르다 — 로그는 운영 중 실시간 관찰/장애 추적용이고,
 * 이 테이블은 "누가 언제 누구에게 무엇을 했는지"를 나중에도 조회 가능한 형태로 남기는 영속 감사
 * 기록이다. 서로 대체하지 않고 같이 남긴다.
 *
 * updated_at 컬럼이 없다 — 감사 로그는 append-only라 수정 개념 자체가 없어서 updated_at을 갖는
 * LongMutableBaseEntity를 상속하지 않고 독립 엔티티로 둔다.
 */
@Entity
@Getter
@Table(name = "audit_log")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "audit_log_id")
    private Long id;

    /** 행위 관리자(actor) FK — 이 액션을 수행한 관리자. admin 테이블 참조. */
    @Column(name = "admin_id", nullable = false)
    private Long adminId;

    /** PRODUCT_DELETE/REFUND/GRADE_CHANGE 같은 액션 종류. 이 도메인에서는 ADMIN_REGISTER/ADMIN_DELETE. */
    @Column(nullable = false, length = 50)
    private String action;

    /** 대상 식별자(예: 이 액션이 적용된 관리자/상품/주문의 id 문자열). 자유 형식이라 FK로 묶지 않는다. */
    @Column(length = 100)
    private String target;

    /** 상세 내용. 민감정보(비밀번호 등)는 절대 담지 않는다 — role/loginId 정도의 비민감 메타데이터만. */
    @Column(columnDefinition = "TEXT")
    private String detail;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    private AuditLog(Long adminId, String action, String target, String detail) {
        this.adminId = adminId;
        this.action = action;
        this.target = target;
        this.detail = detail;
    }
}
