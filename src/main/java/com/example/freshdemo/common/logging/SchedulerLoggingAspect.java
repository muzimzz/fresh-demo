package com.example.freshdemo.common.logging;

import java.time.Duration;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * {@code @Scheduled} 메서드 실행마다 시작/종료(또는 실패)/소요시간을 자동으로 로깅하는 공통 Aspect.
 * 스케줄러 클래스가 로깅 코드를 따로 안 짜도 되고, {@code @Scheduled}만 붙이면 이 Aspect가 자동으로
 * 적용된다 — "실행은 됐는데 처리할 게 없어서 로그를 아예 안 남기는" 개별 스케줄러가 있어도, 이
 * Aspect 덕분에 "그 시간에 스케줄러가 정상적으로 돌긴 한 건지"는 최소 1줄로 항상 확인할 수 있다.
 *
 * 지금은 이 Aspect의 대상이 되는 {@code @Scheduled} 메서드가 프로젝트에 하나도 없다(RT DB 백업을
 * refresh_token_backup 별도 테이블에서 member/admin 컬럼으로 옮기면서 RefreshTokenCleanupScheduler가
 * 없어졌다 — 더 이상 정리할 별도 행이 없어서). 그래도 이 Aspect 자체는 재사용 가능한 공통 인프라라
 * 지워지 않고 남겨뒀다 — 나중에 스케줄러가 다시 생기면 {@code @Scheduled}만 붙이면 바로 적용된다.
 */
@Slf4j
@Aspect
@Component
public class SchedulerLoggingAspect {

    @Around("@annotation(org.springframework.scheduling.annotation.Scheduled)")
    public Object logScheduledExecution(ProceedingJoinPoint joinPoint) throws Throwable {
        String job = joinPoint.getSignature().toShortString();
        Instant start = Instant.now();
        log.info("event=SCHEDULER_START job={}", job);

        try {
            Object result = joinPoint.proceed();
            log.info("event=SCHEDULER_END job={} durationMs={}",
                    job, Duration.between(start, Instant.now()).toMillis());
            return result;
        } catch (Throwable ex) {
            log.error("event=SCHEDULER_FAILED job={} durationMs={}",
                    job, Duration.between(start, Instant.now()).toMillis(), ex);
            throw ex;
        }
    }
}
