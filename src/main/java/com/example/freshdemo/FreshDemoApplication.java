package com.example.freshdemo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// @EnableScheduling: RefreshTokenCleanupScheduler(만료된 refreshToken DB 백업 row 정리)의 @Scheduled가
// 동작하려면 필요하다.
@EnableScheduling
@SpringBootApplication
public class FreshDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(FreshDemoApplication.class, args);
    }

}
