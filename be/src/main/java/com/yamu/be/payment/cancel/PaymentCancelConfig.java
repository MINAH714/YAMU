package com.yamu.be.payment.cancel;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 결제 취소의 포트 등록. 실적·한도 복구는 {@link CancellationLedger}(Task 5.2.5), 신용카드 회차 처리는
 * {@link CreditBillingCanceler}(S15P31E103-200 Merge 후). 실제 빈이 들어오면 여기의 임시 구현은 만들지 않는다
 * ({@code PaymentApprovalConfig}와 같은 방식).
 */
@Configuration
public class PaymentCancelConfig {

    @Bean
    @ConditionalOnMissingBean(CancellationLedger.class)
    CancellationLedger noopCancellationLedger() {
        return new NoopCancellationLedger();
    }

    @Bean
    @ConditionalOnMissingBean(CreditBillingCanceler.class)
    CreditBillingCanceler noBillItemsCreditBillingCanceler(JdbcTemplate jdbc) {
        return new NoBillItemsCreditBillingCanceler(jdbc);
    }
}
