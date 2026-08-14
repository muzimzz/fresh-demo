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
import org.hibernate.annotations.Check;

/**
 * 컬럼명/제약은 V1__init_schema.sql(목표 DDL)의 member 테이블을 그대로 따른다 — social_type/
 * social_type_id였던 예전 컬럼명을 DDL의 provider/provider_user_id로 맞췄고, active_provider_key도
 * 더 이상 애플리케이션이 직접 값을 넣고 비우는 일반 컬럼이 아니라 deleted_at 기준으로 DB가 계산하는
 * GENERATED 컬럼이다(Address.isDefaultKey와 같은 기법). status/refresh_token 관련 CHECK 제약도
 * DDL 그대로 옮겨왔다.
 *
 * 주의: 이 프로젝트는 Flyway 없이 ddl-auto:update로 스키마를 관리한다. GENERATED 컬럼·CHECK 제약을
 * 기존 테이블에 추가하는 ALTER를 Hibernate가 깨끗하게 만들어내는지는 로컬 검증이 필요하다 — 안
 * 먹으면(컬럼/제약이 안 생기면) member 테이블을 드롭하고 재기동하거나 수동 DDL이 필요하다
 * (Address 엔티티의 같은 주의사항 참고).
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "member")
@Check(name = "chk_member_status", constraints = "status IN ('PENDING_PROFILE','ACTIVE','BLOCKED','WITHDRAWN')")
@Check(name = "chk_member_refresh_token", constraints = "(refresh_token_hash IS NULL AND refresh_token_expires_at IS NULL) "
        + "OR (refresh_token_hash IS NOT NULL AND refresh_token_expires_at IS NOT NULL)")
@Check(name = "chk_member_withdrawn", constraints = "(status = 'WITHDRAWN' AND deleted_at IS NOT NULL) "
        + "OR (status <> 'WITHDRAWN' AND deleted_at IS NULL)")
public class Member extends LongMutableBaseEntity {

    // 목표 DDL 컬럼명(provider). 예전엔 social_type이었다 — 카카오 말고 다른 인증 제공자가 추가될
    // 걸 대비한 확장 컬럼이라는 의미가 DDL 코멘트에 명시돼 있다.
    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 30)
    private SocialType provider;

    // 목표 DDL 컬럼명(provider_user_id, VARCHAR(100)). 카카오 회원번호(OIDC sub, 고유 식별자).
    // unlink API의 target_id, 웹훅의 user_id와 매칭되는 값. 로그 평문 출력 금지(PiiMasker.maskProviderId
    // 사용처 참고). 유니크 제약은 이 컬럼 자체가 아니라 activeProviderKey 하나에만 건다.
    @Column(name = "provider_user_id", nullable = false, length = 100)
    private String providerUserId;

    /**
     * "{provider}:{providerUserId}" 형태의 활성 식별 키 — 목표 DDL대로 deleted_at IS NULL을 기준으로
     * DB가 직접 계산하는 GENERATED 컬럼이다(VIRTUAL, DDL에 STORED 명시 없음). 애플리케이션은 이 값을
     * 더 이상 직접 쓰지 않는다(insertable/updatable=false) — 탈퇴(withdraw())로 deleted_at이 채워지는
     * 순간 이 컬럼도 자동으로 NULL이 되어, 같은 카카오 계정으로 재가입할 때 새 행을 만들 수 있게
     * 자리가 비워진다. 조회는 여전히 buildActiveProviderKey()로 같은 조합 규칙을 써서 검색 키를 만든다.
     */
    @Column(name = "active_provider_key", insertable = false, updatable = false, unique = true, length = 140,
            columnDefinition = "VARCHAR(140) GENERATED ALWAYS AS "
                    + "(CASE WHEN deleted_at IS NULL THEN CONCAT(provider, ':', provider_user_id) ELSE NULL END)")
    private String activeProviderKey;

    // 목표 DDL 코멘트는 "카카오 제공 이메일"이지만, 실제로는 카카오에서 받아오지 않기로 했다 —
    // name과 마찬가지로 온보딩 폼 입력값을 저장한다(completeOnboarding() 참고). 카카오 OIDC의
    // account_email 동의도 더 이상 요청하지 않는다(OAuthAttributes/application.yaml 참고).
    // 카카오 로그인마다 값을 덮어쓰는 코드도 없다 — 이전엔 update(String)로 매 로그인마다
    // 카카오 값을 덮어썼는데, 그 메서드 자체를 없앴다.
    @Column(length = 255)
    private String email;

    // 목표 DDL은 VARCHAR(50)이다(예전엔 20으로 더 좁게 잡혀 있었음 — MemberOnboardingRequest/
    // MemberProfileUpdateRequest의 @Size(max=20)도 함께 50으로 맞춰야 한다).
    // unique=true는 DDL엔 없는 애플리케이션 자체 제약이다(중복 닉네임 방지) — 이 프로젝트가 아는
    // 별도 이슈(existsByNickname 선조회 방식의 동시성 레이스)가 있는 채로 유지 중이며, 이번 라운드의
    // 수정 대상이 아니다.
    @Column(unique = true, length = 50)
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

    // 목표 DDL: VARCHAR(30) COLLATE utf8mb4_0900_as_cs. 서버 기본 콜레이션(utf8mb4_0900_ai_ci)은
    // 대소문자를 구분하지 않아 'pending_profile' 같은 값도 통과시키는데, 애플리케이션은 Enum.valueOf로
    // 대소문자를 구분해서 읽으므로 DB와 애플리케이션의 판단이 어긋날 수 있다 — DDL 주석의 경고 그대로.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "VARCHAR(30) COLLATE utf8mb4_0900_as_cs")
    private MemberStatus status;

    // 목표 DDL의 member.deleted_at과 이름을 맞췄다(기존 withdrawnAt에서 리네임) — DDL은 이 컬럼과
    // status='WITHDRAWN'의 짝을 CHECK 제약(chk_member_withdrawn, 클래스 레벨 @Check)으로 강제한다.
    // withdraw()가 항상 둘을 같이 바꾸므로 정상 경로에서는 위반이 안 나지만, 이 CHECK가 실수로 한쪽만
    // 바꾸는 코드(예: 나중에 누가 deletedAt만 세터로 직접 건드리는 경우)에 대한 DB 레벨 마지막 방어선이다.
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    // 목표 DDL의 refresh_token_hash/refresh_token_expires_at — Redis가 죽었을 때를 대비한 DB
    // 백업이다. 원래는 별도 refresh_token_backup 테이블(role+ownerId 키)이 이 역할을 했는데, DDL을
    // 따라 이 회원 행 자체에 합쳤다. RefreshTokenRepository만 이 두 컬럼을 직접 건드린다(항상 같이
    // 쓰고 같이 비움 — DDL의 chk_member_refresh_token과 같은 "둘 다 있거나 둘 다 없거나" 불변식을
    // 코드로만 지킨다). 값은 항상 SHA-256 해시고 원문은 절대 안 남는다(TokenHasher 참고).
    // 목표 DDL은 CHAR(64)(해시가 항상 정확히 64자 hex라 고정길이) — 예전엔 VARCHAR(64)였다.
    @Column(name = "refresh_token_hash", columnDefinition = "CHAR(64)")
    private String refreshTokenHash;

    @Column(name = "refresh_token_expires_at")
    private LocalDateTime refreshTokenExpiresAt;

    @Builder
    private Member(SocialType provider, String providerUserId, MemberRole role, Long memberGradeId) {
        this.provider = provider;
        this.providerUserId = providerUserId;
        // activeProviderKey는 더 이상 여기서 대입하지 않는다 — DB가 GENERATED 컬럼으로 계산한다
        // (deleted_at이 아직 없는 신규 행이므로 저장 즉시 "{provider}:{providerUserId}"로 채워진다).
        this.role = (role != null) ? role : MemberRole.ROLE_USER;
        this.memberGradeId = Objects.requireNonNull(memberGradeId, "memberGradeId");
        // 카카오 최초 로그인 시점엔 sub(식별자) 말고 아무 프로필 정보도 안 받는다 — email도 이제
        // 온보딩 폼 입력이라(위 email 필드 주석 참고), 필수 온보딩 정보(이름/이메일/닉네임/약관동의)를
        // 받기 전까지는 PENDING_PROFILE로 시작한다 — completeOnboarding() 호출 전까지는 미완성 상태.
        this.status = MemberStatus.PENDING_PROFILE;
    }

    /**
     * activeProviderKey 조합 규칙 — DB의 GENERATED 컬럼 계산식(CONCAT(provider, ':', provider_user_id))과
     * 반드시 같은 규칙을 유지해야 한다. 엔티티 자체는 이 값을 더 이상 저장하지 않지만(DB가 계산),
     * "이 provider/providerUserId 조합으로 활성 회원을 찾는다"는 조회 조건을 만들 때 애플리케이션이
     * 여전히 이 조합 규칙을 알아야 하므로 정적 메서드로 남겨둔다(MemberRepository.findByActiveProviderKey
     * 호출부 참고).
     */
    public static String buildActiveProviderKey(SocialType provider, String providerUserId) {
        return provider.name() + ":" + providerUserId;
    }

    public Member assignNickname(String nickname) {
        this.nickname = nickname;
        return this;
    }

    /**
     * 요구사항의 "회원 정보 관리"(이름/닉네임/이메일/휴대폰/주소 변경). email이 카카오 관리 값이
     * 아니게 되면서(위 email 필드 주석 참고) 더 이상 "카카오가 덮어써서 못 바꾼다"는 제약이 없어져,
     * 다른 필드와 동일하게 이 API로 바꿀 수 있다.
     *
     * phone/address는 null이면 그대로 두고, 빈 문자열("")이면 지운다 — 온보딩과 달리 이 API는
     * 부분 수정(PATCH)이라 "이 필드는 이번에 안 건드린다"와 "이 필드를 비운다"를 구분해야 한다.
     * name/nickname/email은 프로필 전체를 다시 제출하는 폼을 가정해 항상 값이 온다고 본다.
     */
    public Member updateProfile(String name, String nickname, String email, String phone, String address) {
        this.name = name;
        assignNickname(nickname);
        this.email = email;
        if (phone != null) {
            this.phone = phone.isBlank() ? null : phone;
        }
        if (address != null) {
            this.address = address.isBlank() ? null : address;
        }
        return this;
    }

    /**
     * 가입 직후 PENDING_PROFILE 상태에서 필수 온보딩 정보(이름 + 이메일 + 닉네임 + 약관동의)를 채워
     * ACTIVE로 넘긴다. phone/address는 선택 항목이라 여기서 안 받는다(첫 배송 시점에 별도로 받기로 함).
     *
     * 이미 ACTIVE인 회원이 다시 호출해도(정보 변경) 에러 없이 값만 갱신한다 — 상태 전이는
     * PENDING_PROFILE일 때만 일어난다. 약관 동의 자체를 거부하는 경로는 이 메서드에 안 들어온다
     * (요청 DTO의 @AssertTrue가 컨트롤러 진입 전에 이미 막음 — MemberOnboardingRequest 참고).
     */
    public Member completeOnboarding(String name, String nickname, String email, LocalDateTime termsAgreedAt, boolean marketingAgreed) {
        this.name = name;
        assignNickname(nickname);
        this.email = email;
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
        // activeProviderKey를 더 이상 여기서 직접 비우지 않는다 — GENERATED 컬럼이라 deleted_at을
        // 채운 시점에 DB가 자동으로 NULL로 재계산한다. provider/providerUserId 자체는 탈퇴 이력
        // 조회를 위해 그대로 남겨두고, 이후 재가입은 이 행을 재활성화하는 게 아니라 새 행을 만드는
        // 방식으로 처리한다(CustomOidcUserService 참고).
    }

    /**
     * 실수로 엔티티를 통째로 log.info(member)/log.debug(member)처럼 찍어도 email/phone/address/
     * providerUserId 같은 민감정보가 그대로 새어나가지 않도록 방어적으로 오버라이드한다. email은
     * 완전히 빼는 대신 PiiMasker로 부분 마스킹만 해서 남긴다 — 그래도 디버깅할 땐 어떤 계정인지
     * 어느 정도 식별은 돼야 하니까. phone/address/providerUserId/activeProviderKey는 아예 안 남긴다.
     */
    @Override
    public String toString() {
        return "Member{id=%s, nickname=%s, email=%s, status=%s, role=%s}"
                .formatted(getId(), nickname, PiiMasker.maskEmail(email), status, role);
    }
}
