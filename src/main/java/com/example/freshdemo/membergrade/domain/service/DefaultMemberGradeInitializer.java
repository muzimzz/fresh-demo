package com.example.freshdemo.membergrade.domain.service;

import com.example.freshdemo.membergrade.domain.entity.MemberGrade;
import com.example.freshdemo.membergrade.domain.repository.MemberGradeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Member.memberGradeId가 NOT NULL FK로 바뀌면서, 회원가입이 되려면 MemberGrade에 isDefault=true인
 * 행이 최소 1개는 있어야 한다 — 없으면 DEFAULT_MEMBER_GRADE_NOT_FOUND로 가입 자체가 막힌다.
 * Flyway 같은 마이그레이션 도구가 없어(ddl-auto:update) 기동 시점에 확인해 없으면 만들어준다.
 *
 * [LG-fm 컨벤션 리팩토링] membergrade.domain.service로 패키지만 이동, 로직 무변경.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultMemberGradeInitializer implements ApplicationRunner {

    private static final String DEFAULT_GRADE_NAME = "일반";

    private final MemberGradeRepository memberGradeRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (memberGradeRepository.findByIsDefaultTrue().isPresent()) {
            return;
        }

        if (memberGradeRepository.existsByName(DEFAULT_GRADE_NAME)) {
            log.warn("event=DEFAULT_MEMBER_GRADE_SEED_SKIPPED reason=NAME_EXISTS_BUT_NOT_DEFAULT name={}", DEFAULT_GRADE_NAME);
            return;
        }

        memberGradeRepository.save(MemberGrade.register(DEFAULT_GRADE_NAME, null, true));
        log.info("event=DEFAULT_MEMBER_GRADE_SEEDED name={}", DEFAULT_GRADE_NAME);
    }
}
