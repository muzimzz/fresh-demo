package com.example.freshdemo.member.repository;

import com.example.freshdemo.member.domain.Member;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MemberRepository extends JpaRepository<Member, UUID> {
    /** 현재 "활성" 상태로 특정 소셜 계정에 매인 회원을 찾는다. 탈퇴한 회원은 activeProviderKey가
     *  null이라 여기 걸리지 않는다 — Member.buildActiveProviderKey() 참고. */
    Optional<Member> findByActiveProviderKey(String activeProviderKey);

    boolean existsByNickname(String nickname);

    Optional<Member> findByNickname(String nickname);
}
