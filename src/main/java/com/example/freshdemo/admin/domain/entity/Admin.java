package com.example.freshdemo.admin.domain.entity;

import com.example.freshdemo.common.entity.BaseMutableTimeEntity;
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
 * 백오피스 관리자 계정. V1__init_schema.sql의 admin 테이블 그대로.
 *
 * [LG-fm 컨벤션 리팩토링] admin.domain.entity로 이동, BaseMutableTimeEntity 교체, 생성 패턴을
 * @Builder(access=PRIVATE) + register() 정적 팩토리로 전환. 생성자 검증을 loginId/passwordHash/
 * name까지 확장(원래는 검증 없이 그대로 대입).
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
public class Admin extends BaseMutableTimeEntity {

    @Column(name = "login_id", nullable = false, unique = true, length = 50)
    private String loginId;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(nullable = false, length = 50)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "VARCHAR(30) COLLATE utf8mb4_0900_as_cs")
    private AdminRole role;

    @Column(name = "refresh_token_hash", columnDefinition = "CHAR(64)")
    private String refreshTokenHash;

    @Column(name = "refresh_token_expires_at")
    private LocalDateTime refreshTokenExpiresAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "VARCHAR(30) COLLATE utf8mb4_0900_as_cs")
    private AdminStatus status;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Builder(access = AccessLevel.PRIVATE)
    private Admin(String loginId, String passwordHash, String name, AdminRole role) {
        this.loginId = Objects.requireNonNull(loginId, "loginId");
        this.passwordHash = Objects.requireNonNull(passwordHash, "passwordHash");
        this.name = Objects.requireNonNull(name, "name");
        this.role = (role != null) ? role : AdminRole.ADMIN;
        this.status = AdminStatus.ACTIVE;
    }

    /** 관리자 계정 발급 — 유일한 생성 진입점. role은 항상 ADMIN으로 시작한다(SUPER_ADMIN 승격은 별도 절차). */
    public static Admin register(String loginId, String passwordHash, String name) {
        return Admin.builder()
                .loginId(loginId)
                .passwordHash(passwordHash)
                .name(name)
                .role(AdminRole.ADMIN)
                .build();
    }

    public boolean isDeleted() {
        return this.status == AdminStatus.DELETED;
    }

    public void changePassword(String encodedPassword) {
        this.passwordHash = encodedPassword;
    }

    public void delete() {
        if (isDeleted()) {
            return;
        }
        this.status = AdminStatus.DELETED;
        this.deletedAt = LocalDateTime.now();
    }

    @Override
    public String toString() {
        return "Admin{id=%s, loginId=%s, name=%s, role=%s, status=%s}"
                .formatted(getId(), loginId, PiiMasker.maskName(name), role, status);
    }
}
