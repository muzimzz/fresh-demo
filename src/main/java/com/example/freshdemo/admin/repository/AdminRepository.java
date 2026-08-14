package com.example.freshdemo.admin.repository;

import com.example.freshdemo.admin.domain.Admin;
import com.example.freshdemo.admin.domain.AdminRole;
import com.example.freshdemo.admin.domain.AdminStatus;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AdminRepository extends JpaRepository<Admin, Long> {

    // login_id는 삭제된 계정도 값을 재사용하지 않는 UNIQUE라, 상태와 무관하게 전체에서 찾는다
    // (existsByLoginId도 마찬가지 이유로 상태 필터가 없어야 한다 — 이미 그렇게 되어 있음).
    Optional<Admin> findByLoginId(String loginId);

    boolean existsByLoginId(String loginId);

    /** "최고관리자 1명 이상 유지" 규칙 검사용 — AdminService.deleteAdmin()의 마지막 SUPER_ADMIN 보호. */
    long countByRoleAndStatus(AdminRole role, AdminStatus status);

    /** refreshToken DB 백업(RefreshTokenRepository 전용). MemberRepository의 동명 메서드 참고. */
    @Modifying
    @Query("update Admin a set a.refreshTokenHash = :hash, a.refreshTokenExpiresAt = :expiresAt where a.id = :id")
    int updateRefreshToken(@Param("id") Long id, @Param("hash") String hash, @Param("expiresAt") LocalDateTime expiresAt);

    @Modifying
    @Query("update Admin a set a.refreshTokenHash = null, a.refreshTokenExpiresAt = null where a.id = :id")
    int clearRefreshToken(@Param("id") Long id);

    @Modifying
    @Query("update Admin a set a.refreshTokenHash = :newHash, a.refreshTokenExpiresAt = :expiresAt "
            + "where a.id = :id and a.refreshTokenHash = :oldHash")
    int compareAndSetRefreshToken(@Param("id") Long id, @Param("oldHash") String oldHash,
                                   @Param("newHash") String newHash, @Param("expiresAt") LocalDateTime expiresAt);
}
