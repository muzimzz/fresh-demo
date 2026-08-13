package com.example.freshdemo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// @EnableScheduling: 지금은 @Scheduled 메서드가 프로젝트에 하나도 없어서(RT DB 백업을 별도 테이블 +
// 정리 스케줄러 방식에서 member/admin 컬럼 방식으로 옮기며 없어짐) 당장 이 애너테이션이 하는 일은
// 없다. 그래도 켜놔서 해로울 게 없고, 나중에 스케줄러가 다시 필요해지면 바로 쓸 수 있어 유지한다.
@EnableScheduling
@SpringBootApplication
public class FreshDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(FreshDemoApplication.class, args);
    }

}
