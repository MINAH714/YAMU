package com.yamu.be.payment.billing;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 신용카드 청구 정기 작업 설정({@code yamu.billing.*}, API 명세 V2 4장 「정기 작업」).
 *
 * @param confirmCron              청구 확정 작업 시각(한국 시간 cron). 명세: 매일 00:30
 * @param withdrawCron             청구 출금 작업 시각(한국 시간 cron). 명세: 매일 07:00
 * @param withdrawRecoveryInterval 결과를 모르는 청구 출금을 거래내역으로 확인하는 주기. 새 출금은 하지 않는다(2026-11-05 결정, 10분)
 * @param refundPayoutCron         환급 입금 작업 시각(한국 시간 cron). 명세: 매일 09:00
 * @param jobsEnabled              정기 작업을 돌릴지. 기본은 끔이고 운영은 환경변수로 켠다. 수동 실행 API(「[운영] 정기 작업 수동 실행」)는 이 값과 상관없다
 */
@Validated
@ConfigurationProperties(prefix = "yamu.billing")
public record BillingProperties(@NotBlank String confirmCron, @NotBlank String withdrawCron,
        @NotNull Duration withdrawRecoveryInterval, @NotBlank String refundPayoutCron, boolean jobsEnabled) {
}
