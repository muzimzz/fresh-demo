package com.example.freshdemo.membergrade.domain;

import com.example.freshdemo.common.jpa.LongMutableBaseEntity;
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
 * PK는 Long — 지금은 프로젝트 전체가 Long PK로 통일되어 있어 다른 엔티티와 다를 게 없지만,
 * 원래도 외부에 노출되지 않는 내부 참조용 테이블이라 애초부터 Long이었다(LongMutableBaseEntity 참고).
 *
 * 등급별 혜택(할인율 등)은 아직 없다 — 목표 DDL도 member_grade는 등급 구분만 하고 혜택을
 * 갖지 않는다(등급 할인은 "혜택 형태를 정할 때 함께 본다"고 미뤄둔 항목). 원래 이 엔티티엔
 * discountRate(BigDecimal) 컬럼이 있었는데, 아무도 안 읽는 값이라 DDL에 맞춰 제거했다 —
 * 등급 혜택을 실제로 설계할 때 그 형태(컬럼으로 둘지, 쿠폰의 target_grade_id처럼 별도
 * 메커니즘으로 표현할지)를 다시 정한다.
 *
 * Member.memberGradeId가 이 표를 NOT NULL FK로 참조한다 — 신규 회원 생성 시 isDefault=true인
 * 행을 자동으로 찾아 배정한다(CustomOidcUserService 참고). 그래서 이 표엔 최소 1개, isDefault=true인
 * 행이 항상 존재해야 한다 — 없으면 회원가입 자체가 막힌다(DEFAULT_MEMBER_GRADE_NOT_FOUND).
 */
@Entity
@Getter
@Table(name = "member_grade")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberGrade extends LongMutableBaseEntity {

    @Column(nullable = false, unique = true, length = 50)
    private String name;

    @Column(name = "promotion_rule", length = 255)
    private String promotionRule;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault;

    @Builder
    private MemberGrade(String name, String promotionRule, boolean isDefault) {
        this.name = Objects.requireNonNull(name, "name");
        this.promotionRule = promotionRule;
        this.isDefault = isDefault;
    }

    @Override
    public String toString() {
        return "MemberGrade{id=%s, name=%s, isDefault=%s}".formatted(getId(), name, isDefault);
    }
}
