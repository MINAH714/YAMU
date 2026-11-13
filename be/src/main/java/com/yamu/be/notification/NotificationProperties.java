package com.yamu.be.notification;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 알림 정기 작업 설정({@code yamu.notification.*}, Task 9.3.2).
 *
 * @param balanceRiskCron         잔고 위험 판정 작업이 깨는 시각(한국 시간 cron). 매분 깨어 받을 시각이 된 사용자만 판정한다
 * @param performanceShortfallCron 실적 부족 판정 작업이 깨는 시각(한국 시간 cron, Task 9.3.3). 잔고 위험과 같은 방식이다
 * @param jobsEnabled             정기 작업을 돌릴지. 기본은 끔이고 운영은 환경변수 {@code NOTIFICATION_JOBS_ENABLED}로 켠다.
 *                                수동 실행 API(「[운영] 정기 작업 수동 실행」)는 이 값과 상관없다
 */
@Validated
@ConfigurationProperties(prefix = "yamu.notification")
public record NotificationProperties(@NotBlank String balanceRiskCron, @NotBlank String performanceShortfallCron,
        boolean jobsEnabled) {
}
