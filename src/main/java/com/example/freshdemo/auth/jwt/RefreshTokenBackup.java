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
 *
 * 저장하는 값은 refreshToken 원문이 아니라 SHA-256 해시(TokenHasher)다 — 이 테이블/Redis가
 * 유출돼도 저장된 값을 그대로 제시해서 로그인할 수 없게 하기 위함. 원문은 클라이언트(httpOnly
 * 쿠키)만 들고 있고, 서버는 "들어온 값의 해시가 저장된 해시와 같은지"만 비교한다.
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

    // SHA-256 해시는 항상 64자(hex) 고정 길이.
    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Builder
    private RefreshTokenBackup(String role, UUID ownerId, String tokenHash, LocalDateTime expiresAt) {
        this.role = role;
        this.ownerId = ownerId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }

    public void rotate(String tokenHash, LocalDateTime expiresAt) {
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }

    public boolean isExpired(LocalDateTime now) {
        return expiresAt.isBefore(now);
    }

    /**
     * tokenHash는 해시라 원문처럼 세션 탈취로 직결되진 않지만(역산 불가), 굳이 로그에 남길 이유도
     * 없어서 방어적으로 계속 제외한다.
     */
    @Override
    public String toString() {
        return "RefreshTokenBackup{id=%s, role=%s, ownerId=%s, expiresAt=%s}"
                .formatted(getId(), role, ownerId, expiresAt);
    }
}
