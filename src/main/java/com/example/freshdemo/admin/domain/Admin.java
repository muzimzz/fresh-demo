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

/**
 * 백오피스 관리자 계정. fm-backend(freshmarket)의 Admin 엔티티를 참고해 가져왔다.
 * PK는 fm-backend와 동일하게 Long AUTO_INCREMENT — 원래는 열거(enumeration) 공격 방지를 위해
 * UUID(v7)를 쓰다가, 이후 프로젝트 전역을 Long PK로 통일하기로 결정하며 바뀌었다
 * (트레이드오프는 LongMutableBaseEntity, DESIGN_NOTES.md 참고).
 *
 * 비밀번호 확인은 엔티티가 아니라 AdminService에서 PasswordEncoder로 한다(엔티티에 인코더 의존성을
 * 넣지 않기 위함 — Member 쪽에 별도 비밀번호 필드가 없는 것과 같은 이유로 도메인을 얇게 유지).
 */
@Entity
@Getter
@Table(name = "admin")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Admin extends LongMutableBaseEntity {

    @Column(name = "login_id", nullable = false, unique = true, length = 50)
    private String loginId;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(nullable = false, length = 50)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AdminRole role;

    // 목표 DDL의 admin 테이블엔 이 두 컬럼이 없다 — DDL 작성자가 관리자 세션의 Redis 장애 대비까지는
    // 고려하지 않은 것으로 보인다. 그래도 관리자도 회원과 동일하게 refreshToken을 발급받고
    // RefreshTokenRepository를 공유해서 쓰므로(회원/관리자 로그인 로직이 본질적으로 같다는 판단,
    // AuthController 참고), DB 백업이 회원만 있고 관리자만 없으면 비대칭이 생긴다 — Member와
    // 대칭으로 여기도 추가했다(Member.refreshTokenHash 주석 참고).
    @Column(name = "refresh_token_hash", length = 64)
    private String refreshTokenHash;

    @Column(name = "refresh_token_expires_at")
    private LocalDateTime refreshTokenExpiresAt;

    @Builder
    private Admin(String loginId, String passwordHash, String name, AdminRole role) {
        this.loginId = loginId;
        this.passwordHash = passwordHash;
        this.name = name;
        this.role = (role != null) ? role : AdminRole.ADMIN;
    }

    /**
     * passwordHash는 절대 로그에 남으면 안 되는 값이라 toString()에서 완전히 제외한다.
     * name은 실명일 수 있어 마스킹, loginId는 계정을 식별해야 디버깅이 되니 그대로 남긴다
     * (비밀번호 자체가 아니라 "아이디"라 유출 위험도가 다르다).
     */
    @Override
    public String toString() {
        return "Admin{id=%s, loginId=%s, name=%s, role=%s}"
                .formatted(getId(), loginId, PiiMasker.maskName(name), role);
    }
}
