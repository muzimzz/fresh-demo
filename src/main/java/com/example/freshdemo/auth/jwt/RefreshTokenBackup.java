package com.example.freshdemo.auth.jwt;

import com.example.freshdemo.common.jpa.MutableBaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Redis가 다운됐을 때를 대비한 refreshToken DB 백업.
 *
 * 평소엔 Redis만 조회/삭제한다(빠르니까) — 이 테이블은 (1) Redis 장애 시의 폴백 조회/CAS,
 * (2) Redis 자체가 죽어도 세션 정보가 완전히 유실되지 않게 하는 안전망 용도로만 쓴다.
 * Redis 키(refreshToken:{role}:{id})와 같은 규칙으로 (role, ownerId) 조합에 유니크를 건다 —
 * 한 회원/관리자당 활성 세션은 하나뿐이라는 기존 설계를 그대로 따른다.
 *
 * Redis는 TTL이 지나면 키가 알아서 사라지지만 이 테이블은 그렇지 않다 —
 * RefreshTokenCleanupScheduler가 주기적으로 만료된 row를 지워준다.
 */
@Entity
@Getter
@Table(
        name = "refresh_token_backup",
        uniqueConstraints = @UniqueConstraint(columnNames = {"role", "owner_id"})
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefreshTokenBackup extends MutableBaseEntity {

    @Column(nullable = false, length = 50)
    private String role;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    // JWT는 대체로 200~400자 안팎이지만 클레임이 늘어날 걸 대비해 여유 있게 잡음
    @Column(nullable = false, length = 500)
    private String token;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Builder
    private RefreshTokenBackup(String role, UUID ownerId, String token, LocalDateTime expiresAt) {
        this.role = role;
        this.ownerId = ownerId;
        this.token = token;
        this.expiresAt = expiresAt;
    }

    public void rotate(String token, LocalDateTime expiresAt) {
        this.token = token;
        this.expiresAt = expiresAt;
    }

    public boolean isExpired(LocalDateTime now) {
        return expiresAt.isBefore(now);
    }
}
