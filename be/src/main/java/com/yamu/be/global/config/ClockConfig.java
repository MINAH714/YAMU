package com.yamu.be.global.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 서비스 코드가 현재 시각을 얻는 시계. {@code LocalDateTime.now(clock)}처럼 쓴다.
 *
 * <p>직접 {@code now()}를 부르지 않고 주입받는 이유: 잠금 30분 뒤 자동 해제처럼 시간이 흘러야 하는 동작을
 * 테스트에서 시계를 옮겨 확인할 수 있게 하려고. 저장은 전부 UTC다.
 */
@Configuration
public class ClockConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
