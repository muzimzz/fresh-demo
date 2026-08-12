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
 * 각 스케줄러 클래스(RefreshTokenCleanupScheduler 등)가 로깅 코드를 따로 안 짜도 되고, 나중에
 * 스케줄러가 늘어나도 {@code @Scheduled}만 붙이면 이 Aspect가 자동으로 적용된다.
 *
 * RefreshTokenCleanupScheduler는 지금 "삭제 건수가 0이면 로그를 아예 안 남기는" 방식이라, 그 시간에
 * 스케줄러가 정상적으로 돌긴 한 건지를 로그만으로 확인할 수 없었다 — 이 Aspect가 스케줄러 코드를
 * 안 건드리고 그 문제를 공통으로 해결한다(매 실행마다 최소 1줄은 항상 남음). 개별 스케줄러가 남기는
 * "deletedCount=3" 같은 구체적인 처리 결과 로그는 그대로 유지하고, 이 Aspect는 그 위에 "언제
 * 시작해서 언제 끝났는지/실패했는지"라는 공통 레이어를 얹는 것뿐이다.
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
