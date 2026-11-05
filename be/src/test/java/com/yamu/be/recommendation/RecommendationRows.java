package com.yamu.be.recommendation;

import com.yamu.be.card.domain.*;
import com.yamu.be.recommendation.domain.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

/** 저장 조회를 대체하는 단위 테스트용 행. 실제 상품 정책이 아니다. */
final class RecommendationRows {
    static <T> T row(Class<T> type, Object... fields) {
        T value = BeanUtils.instantiateClass(type);
        for (int i = 0; i < fields.length; i += 2) {
            ReflectionTestUtils.setField(value, (String) fields[i], fields[i + 1]);
        }
        return value;
    }

    static UserCard card(long id) {
        return row(UserCard.class, "id", id, "userId", 1L, "cardProductId", 10L,
                "registrationStatus", CardRegistrationStatus.ACTIVE, "issuerStatus", CardIssuerStatus.NORMAL);
    }

    static CardProduct product() {
        return row(CardProduct.class, "id", 10L, "cardType", CardType.CREDIT, "productName", "가상 상품");
    }

    static CardBenefit benefit(long id) {
        return row(CardBenefit.class, "id", id, "cardProductId", 10L, "benefitCode", "BENEFIT_" + id,
                "policyVersion", 1, "benefitName", "가상 혜택", "benefitType", BenefitPolicy.BenefitType.DISCOUNT,
                "calculationType", BenefitPolicy.CalculationType.RATE, "rate", new BigDecimal("0.05"),
                "valueUnit", BenefitPolicy.Unit.KRW, "minPaymentAmount", 0L, "minPerformanceAmount", 300_000L,
                "roundingMode", "DOWN", "roundingUnit", 1, "roundingStage", BenefitPolicy.RoundingStage.PER_TRANSACTION,
                "validFrom", LocalDate.of(2026, 1, 1), "validUntil", BenefitPolicy.NO_END_DATE);
    }

    static LimitGroup group() {
        return row(LimitGroup.class, "id", 100L, "cardProductId", 10L, "groupCode", "SHARED",
                "policyVersion", 1, "periodType", BenefitPolicy.Period.MONTHLY, "limitUnit", BenefitPolicy.Unit.KRW,
                "baseLimitValue", 5_000L, "validFrom", LocalDate.of(2026, 1, 1), "validUntil", BenefitPolicy.NO_END_DATE);
    }

    static BenefitLimitLink link(long benefit) {
        return row(BenefitLimitLink.class, "cardProductId", 10L, "cardBenefitId", benefit, "limitGroupId", 100L);
    }

    static RecommendationPolicyData empty() {
        return new RecommendationPolicyData(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }
}
