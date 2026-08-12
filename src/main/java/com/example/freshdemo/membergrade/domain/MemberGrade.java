package com.example.freshdemo.membergrade.domain;

import com.example.freshdemo.common.jpa.LongMutableBaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 회원 등급(단골 할인율 등). fm-backend(freshmarket)의 MemberGrade를 그대로 참고해 가져왔다.
 * PK는 UUID가 아니라 Long — 외부에 노출되지 않는 내부 참조용 테이블이라 fm-backend 컨벤션을
 * 그대로 따랐다(LongMutableBaseEntity 참고).
 *
 * 지금 fresh-demo는 이 테이블(엔티티+레포지토리)만 만들어둔 상태고, Member와의 연관관계
 * (memberGradeId 같은 FK)는 아직 안 걸었다 — 걸려면 "신규 가입 시 기본 등급을 어떻게 자동
 * 배정할지"까지 같이 설계해야 하는데 이번 스코프에는 안 넣기로 함(DESIGN_NOTES.md 참고).
 */
@Entity
@Getter
@Table(name = "member_grade")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberGrade extends LongMutableBaseEntity {

    @Column(nullable = false, unique = true, length = 50)
    private String name;

    @Column(name = "discount_rate", nullable = false, precision = 5, scale = 2)
    private BigDecimal discountRate;

    @Column(name = "promotion_rule", length = 255)
    private String promotionRule;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault;

    @Builder
    private MemberGrade(String name, BigDecimal discountRate, String promotionRule, boolean isDefault) {
        this.name = Objects.requireNonNull(name, "name");
        this.discountRate = Objects.requireNonNull(discountRate, "discountRate");
        this.promotionRule = promotionRule;
        this.isDefault = isDefault;
    }

    @Override
    public String toString() {
        return "MemberGrade{id=%s, name=%s, isDefault=%s}".formatted(getId(), name, isDefault);
    }
}
