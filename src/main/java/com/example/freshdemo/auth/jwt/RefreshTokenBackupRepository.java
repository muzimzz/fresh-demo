package com.example.freshdemo.auth.jwt;

import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface RefreshTokenBackupRepository extends JpaRepository<RefreshTokenBackup, Long> {

    Optional<RefreshTokenBackup> findByRoleAndOwnerId(String role, Long ownerId);

    void deleteByRoleAndOwnerId(String role, Long ownerId);

    /**
     * Redis의 Lua CAS와 같은 역할을 DB만으로 흉내낸다 — WHERE 절에 옛 토큰 값을 같이 걸어서,
     * 실제로 몇 건이 바뀌었는지(영향받은 row 수)로 성공/실패를 판단한다.
     * Redis가 완전히 죽었을 때만 타는 경로라서 그 상황에서만 의미가 있다.
     *
     * @return 교체된 row 수. 0이면 옛 토큰이 안 맞았거나(이미 다른 요청이 먼저 바꿨거나 재사용) 행이 없다는 뜻.
     */
    @Modifying
    @Query("update RefreshTokenBackup r set r.tokenHash = :newTokenHash, r.expiresAt = :expiresAt " +
            "where r.role = :role and r.ownerId = :ownerId and r.tokenHash = :oldTokenHash")
    int compareAndSet(String role, Long ownerId, String oldTokenHash, String newTokenHash, LocalDateTime expiresAt);

    @Modifying
    @Query("delete from RefreshTokenBackup r where r.expiresAt < :now")
    int deleteAllExpiredBefore(LocalDateTime now);
}
