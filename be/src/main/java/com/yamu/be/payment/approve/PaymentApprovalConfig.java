package com.yamu.be.payment.approve;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 결제 승인의 포트 등록. 승인 실행(체크카드 출금 Task 5.2.3, 신용카드 승인·청구 회차 Task 5.2.4)과 원장 반영(Task 5.2.5)이
 * 각각 {@link PaymentApprover}·{@link ApprovalLedger} 빈으로 들어오면 여기의 임시 구현은 만들지 않는다
 * ({@code PaymentRecommendConfig}의 Mock 추천과 같은 방식).
 */
@Configuration
@EnableConfigurationProperties(PaymentApprovalProperties.class)
public class PaymentApprovalConfig {

    @Bean
    @ConditionalOnMissingBean(PaymentApprover.class)
    PaymentApprover temporaryPaymentApprover() {
        return new TemporaryPaymentApprover();
    }

    @Bean
    @ConditionalOnMissingBean(ApprovalLedger.class)
    ApprovalLedger noopApprovalLedger() {
        return new NoopApprovalLedger();
    }
}
