package com.yamu.be.payment.cancel;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 결제 취소의 포트 등록. 실적·한도 복구는 {@link CancellationLedger}(Task 5.2.5). 실제 빈이 들어오면 여기의 임시 구현은
 * 만들지 않는다({@code PaymentApprovalConfig}와 같은 방식). 신용카드 회차 처리는 {@link BillItemCreditBillingCanceler}다.
 */
@Configuration
public class PaymentCancelConfig {

    @Bean
    @ConditionalOnMissingBean(CancellationLedger.class)
    CancellationLedger noopCancellationLedger() {
        return new NoopCancellationLedger();
    }
}
