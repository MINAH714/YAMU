package com.yamu.be.payment.billing;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 신용카드 청구 정기 작업 설정({@code yamu.billing.*}, API 명세 V2 4장 「정기 작업」).
 *
 * @param confirmCron      청구 확정 작업 시각(한국 시간 cron). 명세: 매일 00:30
 * @param refundPayoutCron 환급 입금 작업 시각(한국 시간 cron). 명세: 매일 09:00
 * @param jobsEnabled      정기 작업을 돌릴지. 기본은 끔이고 운영은 환경변수로 켠다. 수동 실행 API(「[운영] 정기 작업 수동 실행」)는 이 값과 상관없다
 */
@Validated
@ConfigurationProperties(prefix = "yamu.billing")
public record BillingProperties(@NotBlank String confirmCron, @NotBlank String refundPayoutCron,
        boolean jobsEnabled) {
}
