package com.yamu.be.payment.billing;

import com.yamu.be.payment.billing.confirm.BillingDiscountSource;
import com.yamu.be.payment.billing.confirm.ZeroBillingDiscountSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 청구 정기 작업의 설정·포트 등록. 청구할인은 {@link BillingDiscountSource}(BE3 혜택 원장, Task 5.2.5)가 정하고, 실제 빈이 들어오면
 * 여기의 임시 구현은 만들지 않는다({@code PaymentApprovalConfig}와 같은 방식).
 */
@Configuration
@EnableConfigurationProperties(BillingProperties.class)
public class BillingConfig {

    @Bean
    @ConditionalOnMissingBean(BillingDiscountSource.class)
    BillingDiscountSource zeroBillingDiscountSource() {
        return new ZeroBillingDiscountSource();
    }
}
