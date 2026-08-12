package com.example.freshdemo.auth.jwt;

import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * RefreshTokenBackup(MySQL)은 Redis와 달리 TTL이 지나도 row가 자동으로 없어지지 않는다.
 * 방치하면 테이블이 무한정 커지니 주기적으로 만료된 row를 지워준다.
 *
 * 삭제 대상 판단만 하는 배치라 Redis 상태와는 무관하게 동작한다 — Redis에서 이미 갱신/삭제된
 * 세션이라도 DB 백업 쪽 expiresAt이 지났으면 그냥 지운다(Redis가 최신 상태를 어떻게 들고 있는지와
 * 상관없이, "이 백업 row는 더 이상 유효하지 않은 시점이 지났다"는 사실만으로 정리 대상이 되는 것).
 *
 * 실행 시작/종료/소요시간은 SchedulerLoggingAspect가 공통으로 남겨준다 — 여기서는 "몇 건 지웠는지"
 * 같은 이 스케줄러만의 구체적인 처리 결과만 로깅한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RefreshTokenCleanupScheduler {

    private final RefreshTokenBackupRepository refreshTokenBackupRepository;

    @Scheduled(cron = "0 0 * * * *") // 매시 정각
    @Transactional
    public void cleanupExpired() {
        int deleted = refreshTokenBackupRepository.deleteAllExpiredBefore(LocalDateTime.now());
        if (deleted > 0) {
            log.info("event=REFRESH_TOKEN_BACKUP_CLEANUP deletedCount={}", deleted);
        }
    }
}
