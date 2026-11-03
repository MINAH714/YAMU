package com.yamu.be.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 정기 작업({@code @Scheduled})을 켠다. 첫 작업은 결과 불명 결제 복구(Task 5.2.3)이고, 공휴일 동기화(Task 5.4.6) 등이 뒤따른다.
 * 작업마다 켜고 끄는 설정은 각 작업 클래스의 {@code @ConditionalOnProperty}로 둔다.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
