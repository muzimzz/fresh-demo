package com.example.freshdemo.admin.domain;

import com.example.freshdemo.common.jpa.LongMutableBaseEntity;
import com.example.freshdemo.common.logging.PiiMasker;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Check;

/**
 * 백오피스 관리자 계정. fm-backend(freshmarket)의 Admin 엔티티를 참고해 가져왔다.
 * PK는 fm-backend와 동일하게 Long AUTO_INCREMENT — 원래는 열거(enumeration) 공격 방지를 위해
 * UUID(v7)를 쓰다가, 이후 프로젝트 전역을 Long PK로 통일하기로 결정하며 바뀌었다
 * (트레이드오프는 LongMutableBaseEntity, DESIGN_NOTES.md 참고).
 *
 * 비밀번호 확인은 엔티티가 아니라 AdminService에서 PasswordEncoder로 한다(엔티티에 인코더 의존성을
 * 넣지 않기 위함 — Member 쪽에 별도 비밀번호 필드가 없는 것과 같은 이유로 도메인을 얇게 유지).
 *
 * CHECK 제약(chk_admin_role/status/deleted/refresh_token)은 V1__init_schema.sql(목표 DDL) 그대로
 * 옮겨왔다 — Member와 같은 이유로 ddl-auto:update 환경에서 기존 테이블에 깨끗하게 반영되는지는
 * 로컬 검증이 필요하다.
 */
@Entity
@Getter
@Table(name = "admin")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Check(name = "chk_admin_role", constraints = "role IN ('SUPER_ADMIN','ADMIN')")
@Check(name = "chk_admin_status", constraints = "status IN ('ACTIVE','DELETED')")
@Check(name = "chk_admin_deleted", constraints = "(status = 'DELETED' AND deleted_at IS NOT NULL AND refresh_token_hash IS NULL) "
        + "OR (status <> 'DELETED' AND deleted_at IS NULL)")
@Check(name = "chk_admin_refresh_token", constraints = "(refresh_token_hash IS NULL AND refresh_token_expires_at IS NULL) "
        + "OR (refresh_token_hash IS NOT NULL AND refresh_token_expires_at IS NOT NULL)")
public class Admin extends LongMutableBaseEntity {

    @Column(name = "login_id", nullable = false, unique = true, length = 50)
    private String loginId;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(nullable = false, length = 50)
    private String name;

    // 목표 DDL: VARCHAR(30) COLLATE utf8mb4_0900_as_cs — Member.status와 같은 이유(대소문자 구분).
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "VARCHAR(30) COLLATE utf8mb4_0900_as_cs")
    private AdminRole role;

    // 처음 DDL 초안엔 admin에 이 두 컬럼이 없어서, 회원/관리자 로그인이 RefreshTokenRepository를
    // 공유하는데 DB 백업이 회원만 있고 관리자만 없으면 비대칭이 생긴다는 이유로 자체적으로
    // 추가했었다(Member.refreshTokenHash 주석과 같은 근거). 이후 받은 DDL 갱신본에 이 두 컬럼이
    // 실제로 추가되어, 이제는 DDL 그대로다 — 자체 추가했던 판단이 맞았던 셈이다.
    // 목표 DDL은 CHAR(64)(고정길이 해시) — 예전엔 VARCHAR(64)였다.
    @Column(name = "refresh_token_hash", columnDefinition = "CHAR(64)")
    private String refreshTokenHash;

    @Column(name = "refresh_token_expires_at")
    private LocalDateTime refreshTokenExpiresAt;

    // 목표 DDL의 admin.status(ACTIVE/DELETED) — 요구사항 정의서의 "관리자 삭제(비활성화)"가
    // "삭제 대신 비활성 처리"를 요구하는데, 이 필드가 없으면 그게 구조적으로 불가능했다(예전엔
    // AdminService.deleteAdmin()이 실제 row를 지웠음). 이제 delete()가 이 값을 DELETED로 바꾸는
    // 방식으로 대체한다. COLLATE는 role과 같은 이유.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "VARCHAR(30) COLLATE utf8mb4_0900_as_cs")
    private AdminStatus status;

    // DDL의 chk_admin_deleted CHECK(클래스 레벨 @Check로 반영): status='DELETED'면 이 값도 채워지고
    // refresh_token_hash는 반드시 NULL이어야 한다. delete() + AdminService가 (deletedAt 세팅 /
    // refreshToken 클리어) 둘을 같은 트랜잭션에서 맞추는 애플리케이션 로직은 그대로 유지 — CHECK는
    // 그 로직에 실수가 생겼을 때의 마지막 방어선이다.
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Builder
    private Admin(String loginId, String passwordHash, String name, AdminRole role) {
        this.loginId = loginId;
        this.passwordHash = passwordHash;
        this.name = name;
        this.role = (role != null) ? role : AdminRole.ADMIN;
        this.status = AdminStatus.ACTIVE;
    }

    public boolean isDeleted() {
        return this.status == AdminStatus.DELETED;
    }

    /** 인코딩된(해시) 비밀번호만 받는다 — 원문 인코딩은 AdminService(PasswordEncoder 보유)의 책임. */
    public void changePassword(String encodedPassword) {
        this.passwordHash = encodedPassword;
    }

    /**
     * 소프트 삭제("비활성 처리"). refreshTokenHash는 여기서 직접 건드리지 않는다 — 그 컬럼은
     * RefreshTokenRepository만 쓰기로 한 규칙이 있어서(Member와 동일), 호출자(AdminService)가
     * 같은 트랜잭션 안에서 refreshTokenRepository.delete()를 같이 호출해 DDL의 CHECK 불변식
     * (DELETED면 refresh_token_hash IS NULL)을 맞춰야 한다.
     */
    public void delete() {
        if (isDeleted()) {
            return;
        }
        this.status = AdminStatus.DELETED;
        this.deletedAt = LocalDateTime.now();
    }

    /**
     * passwordHash는 절대 로그에 남으면 안 되는 값이라 toString()에서 완전히 제외한다.
     * name은 실명일 수 있어 마스킹, loginId는 계정을 식별해야 디버깅이 되니 그대로 남긴다
     * (비밀번호 자체가 아니라 "아이디"라 유출 위험도가 다르다).
     */
    @Override
    public String toString() {
        return "Admin{id=%s, loginId=%s, name=%s, role=%s, status=%s}"
                .formatted(getId(), loginId, PiiMasker.maskName(name), role, status);
    }
}
