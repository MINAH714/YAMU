package com.yamu.be.payment.approve;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 결제 승인의 포트 등록. 승인 실행은 카드 종류로 나뉜다: 체크카드는 {@link CheckCardApprover}(Task 5.2.3, 금융망 출금),
 * 신용카드는 {@link YamuCreditCardApprover}(Task 5.2.4, 청구 회차). 원장 반영은 {@link ApprovalLedger}(Task 5.2.5),
 * 모의 신용한도는 {@link CreditLimitSource}(POL-013 후속 설계). 실제 빈이 들어오면 여기의 임시 구현은 만들지 않는다
 * ({@code PaymentRecommendConfig}의 Mock 추천과 같은 방식).
 */
@Configuration
@EnableConfigurationProperties(PaymentApprovalProperties.class)
public class PaymentApprovalConfig {

    @Bean
    @ConditionalOnMissingBean(CreditLimitSource.class)
    CreditLimitSource unlimitedCreditLimitSource() {
        return new UnlimitedCreditLimitSource();
    }

    @Bean
    @ConditionalOnMissingBean(PaymentApprover.class)
    PaymentApprover cardTypeRoutingApprover(CheckCardApprover check, CreditCardApprover credit) {
        return new CardTypeRoutingApprover(check, credit);
    }

    @Bean
    @ConditionalOnMissingBean(ApprovalLedger.class)
    ApprovalLedger noopApprovalLedger() {
        return new NoopApprovalLedger();
    }
}
