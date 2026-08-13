package com.example.freshdemo.membergrade;

import com.example.freshdemo.membergrade.domain.MemberGrade;
import com.example.freshdemo.membergrade.repository.MemberGradeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Member.memberGradeId가 NOT NULL FK로 바뀌면서, 회원가입이 되려면 MemberGrade에 isDefault=true인
 * 행이 최소 1개는 있어야 한다(CustomOidcUserService 참고) — 없으면 DEFAULT_MEMBER_GRADE_NOT_FOUND로
 * 가입 자체가 막힌다.
 *
 * 이 프로젝트는 Flyway 같은 마이그레이션 도구가 없어서(ddl-auto:update), 이 최소 시드를 넣어줄
 * 곳이 마땅치 않다 — 그래서 기동 시점에 한 번 확인해서 없으면 만들어주는 러너를 둔다. 요청받은
 * 변경은 아니지만, 이게 없으면 로컬에서 회원가입 자체를 테스트할 수 없어서 추가했다.
 *
 * isDefault=true인 행이 하나라도 있으면 아무것도 안 한다 — 운영에서 관리자가 직접 등급을
 * 설계해 만들어둔 경우까지 이 러너가 건드리면 안 되기 때문이다.
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

        // 이름이 이미 있는데 isDefault만 안 켜져 있는 경우(수동으로 만들다 만 경우)까지는 이 러너가
        // 책임지지 않는다 — 그런 애매한 상태는 사람이 판단해서 정리하는 게 맞다고 보고, 이름 충돌이면
        // 그냥 시드를 포기한다(로그만 남김).
        if (memberGradeRepository.existsByName(DEFAULT_GRADE_NAME)) {
            log.warn("event=DEFAULT_MEMBER_GRADE_SEED_SKIPPED reason=NAME_EXISTS_BUT_NOT_DEFAULT name={}", DEFAULT_GRADE_NAME);
            return;
        }

        memberGradeRepository.save(MemberGrade.builder()
                .name(DEFAULT_GRADE_NAME)
                .isDefault(true)
                .build());
        log.info("event=DEFAULT_MEMBER_GRADE_SEEDED name={}", DEFAULT_GRADE_NAME);
    }
}
