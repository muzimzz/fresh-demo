package com.example.freshdemo.membergrade.domain;

import com.example.freshdemo.membergrade.MemberGradeApi;
import com.example.freshdemo.membergrade.domain.entity.MemberGrade;
import com.example.freshdemo.membergrade.domain.repository.MemberGradeRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * MemberGradeApi 구현체. domain-package-boundary-guideline.md의 명명 규칙(~ApiImpl,
 * domain 바로 아래, package-private)을 따른다. ArchUnit이 ApiImpl에 @Transactional을
 * 금지하므로(트랜잭션 경계는 내부 서비스가 진다) 여기서는 단순 조회 위임만 한다.
 */
@Service
@RequiredArgsConstructor
class MemberGradeApiImpl implements MemberGradeApi {

    private final MemberGradeRepository memberGradeRepository;

    @Override
    public Optional<Long> findDefaultGradeId() {
        return memberGradeRepository.findByIsDefaultTrue().map(MemberGrade::getId);
    }
}
