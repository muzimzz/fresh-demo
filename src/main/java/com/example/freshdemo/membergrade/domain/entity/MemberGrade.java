package com.example.freshdemo.membergrade.domain.entity;

import com.example.freshdemo.common.entity.BaseMutableTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 회원 등급(단골 구분용). fm-backend(freshmarket)의 MemberGrade를 참고해 가져왔다.
 *
 * [LG-fm 컨벤션 리팩토링]
 *  1) 패키지를 membergrade.domain.entity로 옮겼다(domain 루트는 API+DTO+예외만, 나머지는
 *     domain.entity/domain.repository/domain.service로 — LG-fm domain-package-boundary 규칙).
 *  2) common.jpa.LongMutableBaseEntity -> common.entity.BaseMutableTimeEntity로 교체
 *     (필드 구성 동일, 클래스명/패키지만 LG-fm 컨벤션에 맞춤).
 *  3) 생성 패턴을 entity-creation-guideline.md에 맞춰 @Builder(access=PRIVATE) + 이름 있는
 *     정적 팩토리(register())로 바꿨다 — 이전엔 생성자에만 @Builder가 붙어 있어 public builder()가
 *     그대로 노출되고 필수값(name) 강제가 컴파일 타임에 안 됐다.
 *
 * Member.memberGradeId가 이 표를 NOT NULL FK로 참조한다 — 신규 회원 생성 시 isDefault=true인
 * 행을 자동으로 찾아 배정한다(CustomOidcUserService 참고). isDefault=true 행 "최대 1개"는
 * isDefaultKey 생성 컬럼 + UNIQUE로 DB가 강제한다(목표 DDL 그대로, Address.isDefaultKey와 같은 기법).
 * "최소 1개"는 DB가 못 막는 조건이라 여전히 DefaultMemberGradeInitializer(기동 시 시드)가 담당한다.
 */
@Entity
@Getter
@Table(name = "member_grade")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberGrade extends BaseMutableTimeEntity {

    @Column(nullable = false, unique = true, length = 50)
    private String name;

    @Column(name = "promotion_rule", length = 255)
    private String promotionRule;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault;

    @Column(name = "is_default_key", insertable = false, updatable = false, unique = true,
            columnDefinition = "TINYINT GENERATED ALWAYS AS (CASE WHEN is_default THEN 1 ELSE NULL END)")
    private Integer isDefaultKey;

    @Builder(access = AccessLevel.PRIVATE)
    private MemberGrade(String name, String promotionRule, boolean isDefault) {
        this.name = Objects.requireNonNull(name, "name");
        this.promotionRule = promotionRule;
        this.isDefault = isDefault;
    }

    /** 등급 정의 — 유일한 생성 진입점. */
    public static MemberGrade register(String name, String promotionRule, boolean isDefault) {
        return MemberGrade.builder()
                .name(name)
                .promotionRule(promotionRule)
                .isDefault(isDefault)
                .build();
    }

    @Override
    public String toString() {
        return "MemberGrade{id=%s, name=%s, isDefault=%s}".formatted(getId(), name, isDefault);
    }
}
