package com.yamu.be.recommendation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yamu.be.card.RecommendationPerformanceAssembler.Recognition;
import com.yamu.be.card.domain.CardProduct;
import com.yamu.be.recommendation.RecommendationInput.Plan;
import com.yamu.be.recommendation.RecommendationInput.Transaction;
import java.io.IOException;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/** 수집 원본에서 검증해 정규화한 상품별 실적 규칙. 혜택 약관/할인 제외 목록을 복사하지 않는다. */
@Component
public class RecommendationRecognitionResolver {
    private final List<Policy> policies;
    private final CardBenefitCalculator calculator = new CardBenefitCalculator();

    public RecommendationRecognitionResolver(ObjectMapper json) {
        try (var input = new ClassPathResource("recommendation/performance-policies.json").getInputStream()) {
            policies = List.copyOf(json.readValue(input, Catalog.class).policies());
        } catch (IOException e) {
            throw new IllegalStateException("실적 정책 원본 매핑을 읽지 못했습니다.", e);
        }
    }

    record Catalog(String source, String sha256, String collectedAt, List<Policy> policies) { }
    record Policy(String cardId, String issuer, String productName, String cardType, Long minimum,
                  String monthRule, boolean fullInstallment, boolean excludeInterestFree, boolean excludeDiscount,
                  List<String> excludedFacts, List<String> excludedCategories, List<String> sourceRefs,
                  List<String> missing, boolean amountRulesKnown, List<String> uncertainCategories) { }

    Recognition resolve(long cardId, CardProduct product, Transaction transaction, BenefitPolicy benefits,
                        List<RecommendationDataIssue> issues) {
        var policy = policies.stream().filter(p -> p.issuer() != null && p.issuer().equals(product.getIssuerCode())
                && p.productName().equals(product.getProductName())
                && p.cardType().equals(product.getCardType().name())).findFirst().orElse(null);
        if (policy == null) {
            issue(issues, cardId, "RECOGNITION_PRODUCT_NOT_IN_SOURCE");
            return new Recognition(cardId, null, null, null);
        }
        Long minimum = policy.minimum();
        if (Long.valueOf(0L).equals(minimum)) {
            // 문턱 없는 혜택이 확인됨. 비음수 누적액에서 before < 0은 불가능하다.
            // 존재하지 않는 실적 기간/인정액을 만들어 채우지 않는다.
            return new Recognition(cardId, null, 0L, null);
        }
        if (minimum != null && minimum > 0) {
            // 앱 등록 시각으로 카드사 최초 발급/사용 등록 및 신규 유예 여부를 추정하지 않는다.
            Boolean introductory = transaction.facts().get("PERFORMANCE:INTRODUCTORY_WAIVER_ACTIVE:" + policy.cardId());
            if (introductory == null) {
                minimum = null;
                issue(issues, cardId, "RECOGNITION_MINIMUM_INTRODUCTORY_STATUS_UNKNOWN:" + policy.cardId());
            } else if (introductory) {
                // 원본의 신규 유예 적용 여부를 담당 기능이 확인한 경우다. 등록 시각에서 추정하지 않는다.
                return new Recognition(cardId, null, 0L, null);
            }
        }
        if (minimum == null && policy.minimum() == null) {
            issue(issues, cardId, "RECOGNITION_MINIMUM_SOURCE_INCOMPLETE:" + policy.cardId());
        }
        YearMonth period = period(policy, transaction);
        if (period == null) {
            issue(issues, cardId, "RECOGNITION_MONTH_UNCONFIRMED:" + policy.cardId());
        }
        Long recognized = recognized(policy, transaction, benefits);
        if (recognized == null) {
            issue(issues, cardId, "RECOGNITION_AMOUNT_UNCONFIRMED:" + policy.cardId());
        }
        return new Recognition(cardId, period, minimum, recognized);
    }

    private YearMonth period(Policy policy, Transaction transaction) {
        if ((policy.monthRule() == null && !(policy.fullInstallment() && transaction.plan() == Plan.INSTALLMENT))
                || !Boolean.TRUE.equals(transaction.facts().get("REGION:DOMESTIC"))) {
            return null; // 해외 매입/접수일은 거래 요청 시각으로 대체하지 않는다.
        }
        Boolean noApproval = transaction.facts().get("TRANSACTION_KIND:UNAPPROVED_VOUCHER");
        if (!Boolean.FALSE.equals(noApproval)) {
            return null;
        }
        var month = YearMonth.from(transaction.occurredAt().atZone(ZoneId.of("Asia/Seoul")));
        if (policy.monthRule() == null) {
            return month; // 일반 기준 사건은 미확인이어도 명시된 할부 승인월 규칙은 독립 적용한다.
        }
        if (policy.monthRule().startsWith("TRANSIT_")) {
            Boolean transit = transaction.facts().get("PERFORMANCE:POSTPAID_TRANSIT");
            if (transit == null) return null;
            if (transit) {
                if (policy.monthRule().equals("TRANSIT_MOBILE_EXCEPTION")) {
                    Boolean mobile = transaction.facts().get("PERFORMANCE:MOBILE_POSTPAID_TRANSIT");
                    if (mobile == null) return null;
                    if (mobile) return month;
                }
                return month.plusMonths(1);
            }
        }
        if (policy.monthRule().equals("PURCHASE_EXCEPT_TELECOM_RECEIPT")) {
            Boolean telecom = transaction.facts().get("PERFORMANCE:TELECOM_AUTOPAY");
            if (!Boolean.FALSE.equals(telecom)) return null;
        }
        return month;
    }

    private Long recognized(Policy policy, Transaction transaction, BenefitPolicy benefits) {
        if (!policy.amountRulesKnown()) return null;
        if (policy.excludeInterestFree() && transaction.plan() == Plan.INSTALLMENT) {
            return 0L; // POL-013 지원 할부는 모의 무이자다. 상품 원본에서 제외된 경우만 0으로 인정한다.
        }
        boolean unknown = false;
        for (var fact : policy.excludedFacts()) {
            Boolean value = transaction.facts().get(fact);
            if (Boolean.TRUE.equals(value)) return 0L;
            unknown |= value == null;
        }
        if (!policy.excludedCategories().isEmpty()) {
            if (policy.excludedCategories().contains(transaction.merchantCategoryCode())) return 0L;
            unknown |= transaction.merchantCategoryCode() == null;
        }
        // 원본에서 UNVERIFIED인 범주는 이번 거래가 그 범주일 때만 미확인으로 남긴다.
        if (!policy.uncertainCategories().isEmpty()) {
            unknown |= transaction.merchantCategoryCode() == null
                    || policy.uncertainCategories().contains(transaction.merchantCategoryCode());
        }
        if (policy.excludeDiscount()) {
            Boolean discounted = transaction.facts().get("PERFORMANCE:PRODUCT_DISCOUNT_APPLIED:" + policy.cardId());
            if (discounted == null) {
                // 기존 계산을 재사용한다. 계산 불가를 할인 없음으로 바꾸지 않는다.
                var calculation = calculator.calculate(transaction, benefits);
                if (calculation.complete()) {
                    discounted = calculation.benefits().stream().anyMatch(b -> b.type() == BenefitPolicy.BenefitType.DISCOUNT
                            && b.value().signum() > 0);
                }
            }
            if (Boolean.TRUE.equals(discounted)) return 0L;
            unknown |= discounted == null;
        }
        if (transaction.plan() == Plan.INSTALLMENT && !policy.fullInstallment()) return null;
        return unknown ? null : transaction.amount();
    }

    private void issue(List<RecommendationDataIssue> issues, long cardId, String code) {
        issues.add(new RecommendationDataIssue(cardId, null, code, false));
    }
}
