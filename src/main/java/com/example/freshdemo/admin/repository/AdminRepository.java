package com.example.freshdemo.admin.repository;

import com.example.freshdemo.admin.domain.Admin;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AdminRepository extends JpaRepository<Admin, Long> {

    Optional<Admin> findByLoginId(String loginId);

    boolean existsByLoginId(String loginId);

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
