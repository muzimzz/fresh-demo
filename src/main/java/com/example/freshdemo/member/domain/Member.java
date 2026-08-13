package com.example.freshdemo.member.domain;

import com.example.freshdemo.common.jpa.LongMutableBaseEntity;
import com.example.freshdemo.common.logging.PiiMasker;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "member")
public class Member extends LongMutableBaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "social_type", nullable = false, length = 20)
    private SocialType socialType;

    @Column(name = "social_type_id", nullable = false)
    private String socialTypeId; // 카카오 회원번호(고유 식별자). unlink API의 target_id, 웹훅의 user_id와 매칭되는 값. 유니크 제약 없음(아래 activeProviderKey 참고)

    /**
     * "{socialType}:{socialTypeId}" 형태의 활성 식별 키. UNIQUE는 이 필드 하나에만 건다 —
     * social_type_id 자체에는 더 이상 유니크 제약이 없다. 탈퇴 시 이 필드를 null로 비워서
     * (withdraw() 참고) 같은 소셜 계정으로 재가입할 때 "새 행"을 만들 수 있게 자리를 비켜준다 —
     * 탈퇴 이력(옛 social_type_id 포함)은 행을 지우지 않고 그대로 남긴 채로.
     * 조회/생성 양쪽이 항상 buildActiveProviderKey()로 같은 규칙을 쓴다.
     */
    @Column(name = "active_provider_key", unique = true, length = 45)
    private String activeProviderKey;

    private String email;

    @Column(unique = true, length = 20)
    private String nickname;

    // 목표 DDL의 member.name(폼 입력 실명) — 카카오가 주는 nickname과는 별개 필드다. nickname은
    // 카카오 프로필 별칭(선택적으로 겹칠 수 있는 값)이고, name은 사용자가 온보딩에서 직접 입력하는
    // 실명이라 서로 대체할 수 없다는 판단으로 DDL을 그대로 따라 분리했다. 온보딩 필수 항목이라
    // PENDING_PROFILE에서는 null, completeOnboarding() 이후엔 항상 값이 있다.
    @Column(length = 50)
    private String name;

    // MemberGrade FK. @ManyToOne 대신 raw id를 든다 — Address.memberId 등과 같은 이유(지연 로딩/N+1
    // 회피). 신규 회원은 항상 MemberGrade.isDefault=true인 행이 자동 배정된다(CustomOidcUserService
    // 참고) — 그래서 NOT NULL이고, 등급을 바꾸는 기능은 아직 없다(고정 배정만 있음).
    @Column(name = "member_grade_id", nullable = false)
    private Long memberGradeId;

    // 약관 동의 시각. null이면 미동의 — boolean 대신 시각을 남겨서 "언제 동의했는지" 감사 추적이 되게 했다.
    @Column(name = "terms_agreed_at")
    private LocalDateTime termsAgreedAt;

    // 필수 약관동의(termsAgreedAt)와 달리 선택 항목 — 온보딩 때 같이 받되 값이 없으면(요청 필드
    // 누락) false로 취급한다. 목표 DDL의 is_marketing_agreed 컬럼을 그대로 들여옴.
    @Column(name = "is_marketing_agreed", nullable = false)
    private boolean marketingAgreed;

    // fm-backend(freshmarket) Member 엔티티의 phone 필드를 참고했다. address는 fm-backend에서는
    // 배송지 여러 개를 관리하는 별도 Address 엔티티(회원당 N개, 기본배송지 플래그)로 빠져 있는데,
    // 이 프로젝트는 주문/배송 도메인이 없는 인증 데모 범위라 그 정도 구조까지는 필요 없다고 판단해
    // 프로필 완성용 단일 문자열 필드로 단순화했다.
    //
    // 주의: phone/address는 온보딩 필수 항목이 아니다 — 닉네임/약관동의(필수)와 달리 첫 배송 시점에
    // 받기로 했다(주문 도메인이 생겨야 걸 수 있는 지점이라 지금은 값을 채우는 진입점이 없다).
    @Column(length = 20)
    private String phone;

    @Column(length = 255)
    private String address;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MemberRole role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MemberStatus status;

    // 목표 DDL의 member.deleted_at과 이름을 맞췄다(기존 withdrawnAt에서 리네임) — DDL은 이 컬럼과
    // status='WITHDRAWN'의 짝을 CHECK 제약으로 강제하는데, 여기서는 그 짝 맞추기를 withdraw()
    // 메서드 하나로만 하고 있다(DB CHECK로 강제하지는 않음 — 이 프로젝트는 Flyway 마이그레이션이
    // 없어 CHECK 제약을 코드만으로 안전하게 걸기 어렵다).
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Builder
    private Member(SocialType socialType, String socialTypeId, String email, MemberRole role, Long memberGradeId) {
        this.socialType = socialType;
        this.socialTypeId = socialTypeId;
        this.activeProviderKey = buildActiveProviderKey(socialType, socialTypeId);
        this.email = email;
        this.role = (role != null) ? role : MemberRole.ROLE_USER;
        this.memberGradeId = Objects.requireNonNull(memberGradeId, "memberGradeId");
        // 카카오 최초 로그인 시 이메일 정도만 갖고 바로 만들어지는 회원이라, 필수 온보딩 정보
        // (닉네임/약관동의)를 받기 전까지는 PENDING_PROFILE로 시작한다 — completeOnboarding()
        // 호출 전까지는 미완성 상태.
        this.status = MemberStatus.PENDING_PROFILE;
    }

    /** activeProviderKey 조합 규칙을 엔티티/조회 로직이 공유하기 위한 단일 지점. */
    public static String buildActiveProviderKey(SocialType socialType, String socialTypeId) {
        return socialType.name() + ":" + socialTypeId;
    }

    public Member update(String email) {
        if (email != null) {
            this.email = email;
        }
        return this;
    }

    public Member assignNickname(String nickname) {
        this.nickname = nickname;
        return this;
    }

    /**
     * 가입 직후 PENDING_PROFILE 상태에서 필수 온보딩 정보(이름 + 닉네임 + 약관동의)를 채워 ACTIVE로
     * 넘긴다. phone/address는 선택 항목이라 여기서 안 받는다(첫 배송 시점에 별도로 받기로 함).
     *
     * 이미 ACTIVE인 회원이 다시 호출해도(정보 변경) 에러 없이 값만 갱신한다 — 상태 전이는
     * PENDING_PROFILE일 때만 일어난다. 약관 동의 자체를 거부하는 경로는 이 메서드에 안 들어온다
     * (요청 DTO의 @AssertTrue가 컨트롤러 진입 전에 이미 막음 — MemberOnboardingRequest 참고).
     */
    public Member completeOnboarding(String name, String nickname, LocalDateTime termsAgreedAt, boolean marketingAgreed) {
        this.name = name;
        assignNickname(nickname);
        this.termsAgreedAt = termsAgreedAt;
        this.marketingAgreed = marketingAgreed;
        if (this.status == MemberStatus.PENDING_PROFILE) {
            this.status = MemberStatus.ACTIVE;
        }
        return this;
    }

    public boolean isPendingProfile() {
        return this.status == MemberStatus.PENDING_PROFILE;
    }

    public boolean isWithdrawn() {
        return this.status == MemberStatus.WITHDRAWN;
    }

    /**
     * 탈퇴 처리. 실제 row는 지우지 않는 소프트 삭제 — 이미 존재하는 참조(친구, 알림 등) 무결성을 지키기 위함.
     *
     * TODO(주문 도메인 추가 시): 요구사항 스펙은 "진행 중인 주문/미완료 환불이 있으면 탈퇴 불가"를 요구한다.
     * 이 프로젝트는 order 모듈이 없는 인증 데모 범위라 지금은 체크하지 않는다 — 실제 freshmarket으로
     * 옮길 때는 여기(또는 호출부인 MemberWithdrawalService.withdraw())에서 진행 중 주문 존재 여부를
     * 먼저 확인해 BusinessException으로 막아야 한다.
     */
    public void withdraw() {
        if (isWithdrawn()) {
            return;
        }
        this.status = MemberStatus.WITHDRAWN;
        this.deletedAt = LocalDateTime.now();
        // 같은 소셜 계정으로 재가입할 수 있게 활성 키를 비워준다. social_type/social_type_id
        // 자체는 탈퇴 이력 조회를 위해 그대로 남겨두되, 이후 재가입은 이 행을 재활성화하는 게 아니라
        // 새 행을 만드는 방식으로 처리한다(CustomOidcUserService 참고.
        this.activeProviderKey = null;
    }

    /**
     * 실수로 엔티티를 통째로 log.info(member)/log.debug(member)처럼 찍어도 email/phone/address/
     * socialTypeId 같은 민감정보가 그대로 새어나가지 않도록 방어적으로 오버라이드한다. email은
     * 완전히 빼는 대신 PiiMasker로 부분 마스킹만 해서 남긴다 — 그래도 디버깅할 땐 어떤 계정인지
     * 어느 정도 식별은 돼야 하니까. phone/address/socialTypeId/activeProviderKey는 아예 안 남긴다.
     */
    @Override
    public String toString() {
        return "Member{id=%s, nickname=%s, email=%s, status=%s, role=%s}"
                .formatted(getId(), nickname, PiiMasker.maskEmail(email), status, role);
    }
}
