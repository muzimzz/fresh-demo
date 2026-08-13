package com.example.freshdemo.member.repository;

import com.example.freshdemo.member.domain.Member;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface MemberRepository extends JpaRepository<Member, Long> {
    /** 현재 "활성" 상태로 특정 소셜 계정에 매인 회원을 찾는다. 탈퇴한 회원은 activeProviderKey가
     *  null이라 여기 걸리지 않는다 — Member.buildActiveProviderKey() 참고. */
    Optional<Member> findByActiveProviderKey(String activeProviderKey);

    boolean existsByNickname(String nickname);

    Optional<Member> findByNickname(String nickname);

    /**
     * refreshToken DB 백업(RefreshTokenRepository 전용). 엔티티를 로드해서 setter로 바꾸지 않고
     * 벌크 UPDATE로 직접 친다 — Redis 장애 시의 폴백 경로라 조회 왕복을 하나라도 줄이는 게 낫고,
     * 어차피 이 값들은 도메인 로직이 들여다볼 일이 없는 순수 인프라용 필드다.
     */
    @Modifying
    @Query("update Member m set m.refreshTokenHash = :hash, m.refreshTokenExpiresAt = :expiresAt where m.id = :id")
    int updateRefreshToken(@Param("id") Long id, @Param("hash") String hash, @Param("expiresAt") LocalDateTime expiresAt);

    @Modifying
    @Query("update Member m set m.refreshTokenHash = null, m.refreshTokenExpiresAt = null where m.id = :id")
    int clearRefreshToken(@Param("id") Long id);

    /**
     * Redis의 Lua CAS와 같은 역할을 DB만으로 흉내낸다 — WHERE 절에 옛 해시를 같이 걸어서, 실제로
     * 몇 건이 바뀌었는지(영향받은 row 수)로 성공/실패를 판단한다. Redis가 완전히 죽었을 때만 타는
     * 경로라서 그 상황에서만 의미가 있다(RefreshTokenBackupRepository.compareAndSet과 동일한 패턴).
     *
     * @return 교체된 row 수. 0이면 옛 해시가 안 맞았거나(이미 다른 요청이 먼저 바꿨거나 재사용) 회원이 없다는 뜻.
     */
    @Modifying
    @Query("update Member m set m.refreshTokenHash = :newHash, m.refreshTokenExpiresAt = :expiresAt "
            + "where m.id = :id and m.refreshTokenHash = :oldHash")
    int compareAndSetRefreshToken(@Param("id") Long id, @Param("oldHash") String oldHash,
                                   @Param("newHash") String newHash, @Param("expiresAt") LocalDateTime expiresAt);
}
