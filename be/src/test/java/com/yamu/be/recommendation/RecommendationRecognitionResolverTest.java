package com.yamu.be.recommendation;

import static org.assertj.core.api.Assertions.*;
import static com.yamu.be.recommendation.RecognitionFixtures.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yamu.be.recommendation.RecommendationInput.*;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RecommendationRecognitionResolverTest {
    final RecommendationRecognitionResolver resolver = new RecommendationRecognitionResolver(new ObjectMapper());
    final ArrayList<RecommendationDataIssue> issues = new ArrayList<>();

    @Test
    void firstCardUsesSourcedThresholdAndTransactionMonthAndCountsInterestFreeAndDiscountedSpending() {
        var facts = ordinaryDomestic("shinhan-cheoeum");
        facts.put("PERFORMANCE:PRODUCT_DISCOUNT_APPLIED:shinhan-cheoeum", true);
        var result = resolver.resolve(1, product("shinhan-cheoeum", 10), tx(10_000, Plan.LUMP_SUM, facts), null, issues);
        assertThat(result.period()).isEqualTo(YearMonth.of(2026, 10));
        assertThat(result.minimumAmount()).isEqualTo(300_000L);
        assertThat(result.recognizedAmount()).isEqualTo(10_000L);
        assertThat(resolver.resolve(1, product("shinhan-cheoeum", 10), tx(50_000, Plan.INSTALLMENT, facts), null, issues)
                .recognizedAmount()).isEqualTo(50_000L);
    }

    @Test
    void excludedInterestFreeAndDiscountedTransactionsAreConfirmedZeroNotUnknown() {
        for (String id : new String[] {"lotte-p13943-a13943", "lotte-p13949-a13949", "lotte-p13955-a13955"}) {
            assertThat(resolver.resolve(1, product(id, 10), tx(50_000, Plan.INSTALLMENT, Map.of()), null, issues)
                    .recognizedAmount()).isZero();
            var facts = ordinaryDomestic(id);
            facts.put("PERFORMANCE:PRODUCT_DISCOUNT_APPLIED:" + id, true);
            assertThat(resolver.resolve(1, product(id, 10), tx(10_000, Plan.LUMP_SUM, facts), null, issues)
                    .recognizedAmount()).isZero();
            facts.remove("PERFORMANCE:PRODUCT_DISCOUNT_APPLIED:" + id);
            assertThat(resolver.resolve(1, product(id, 10), tx(10_000, Plan.LUMP_SUM, facts), null, issues)
                    .recognizedAmount()).isNull();
        }
    }

    @Test
    void knownExclusionWinsEvenIfOtherFactsAreUnknownButMissingFactIsNotFalse() {
        assertThat(resolver.resolve(1, product("shinhan-cheoeum", 10), tx(10_000, Plan.LUMP_SUM,
                Map.of("TRANSACTION_KIND:GIFT_CARD_PURCHASE", true)), null, issues).recognizedAmount()).isZero();
        var facts = ordinaryDomestic("shinhan-cheoeum");
        facts.remove("TRANSACTION_KIND:GIFT_CARD_PURCHASE");
        assertThat(resolver.resolve(1, product("shinhan-cheoeum", 10), tx(10_000, Plan.LUMP_SUM, facts), null, issues)
                .recognizedAmount()).isNull();
    }

    @Test
    void physicalTransitAccruesNextMonthMobileTransitSameMonthAndReceiptMonthIsNotInvented() {
        var facts = ordinaryDomestic("shinhan-cheoeum");
        facts.put("PERFORMANCE:POSTPAID_TRANSIT", true);
        facts.put("PERFORMANCE:MOBILE_POSTPAID_TRANSIT", false);
        assertThat(resolver.resolve(1, product("shinhan-cheoeum", 10), tx(10_000, Plan.LUMP_SUM, facts), null, issues)
                .period()).isEqualTo(YearMonth.of(2026, 11));
        facts.put("PERFORMANCE:MOBILE_POSTPAID_TRANSIT", true);
        assertThat(resolver.resolve(1, product("shinhan-cheoeum", 10), tx(10_000, Plan.LUMP_SUM, facts), null, issues)
                .period()).isEqualTo(YearMonth.of(2026, 10));
        facts.put("REGION:DOMESTIC", false);
        assertThat(resolver.resolve(1, product("shinhan-cheoeum", 10), tx(10_000, Plan.LUMP_SUM, facts), null, issues)
                .period()).isNull();
    }

    @Test
    void initialBenefitStatusUnknownDoesNotEraseKnownRecognitionMonthAndAmount() {
        var facts = ordinaryDomestic("shinhan-cheoeum");
        facts.remove("PERFORMANCE:INTRODUCTORY_WAIVER_ACTIVE:shinhan-cheoeum");
        var result = resolver.resolve(1, product("shinhan-cheoeum", 10), tx(10_000, Plan.LUMP_SUM, facts), null, issues);
        assertThat(result.minimumAmount()).isNull();
        assertThat(result.period()).isEqualTo(YearMonth.of(2026, 10));
        assertThat(result.recognizedAmount()).isEqualTo(10_000L);
        facts.put("PERFORMANCE:INTRODUCTORY_WAIVER_ACTIVE:shinhan-cheoeum", true);
        var waived = resolver.resolve(1, product("shinhan-cheoeum", 10), tx(10_000, Plan.LUMP_SUM, facts), null, issues);
        assertThat(waived.minimumAmount()).isZero();
    }

    @Test
    void noPerformanceRequirementMeansKnownNoNewCrossingWithoutInventingAmountsOrPeriod() {
        var result = resolver.resolve(1, product("hyundai-mzroe3", 10), tx(10_000, Plan.LUMP_SUM, Map.of()), null, issues);
        assertThat(result.minimumAmount()).isZero();
        var progress = new PerformanceProgress(result.period(), null, result.minimumAmount(), result.recognizedAmount());
        assertThat(progress.period()).isNull();
        assertThat(progress.recognizedAmount()).isNull();
        assertThat(progress.isKnown()).isTrue();
        assertThat(progress.newlyMeetsMinimum()).isFalse();
    }

    @Test
    void minimumComesFromProductSourceRatherThanMinimumOfLoadedBenefits() {
        var facts = ordinaryDomestic("shinhan-mrlife");
        var result = resolver.resolve(1, product("shinhan-mrlife", 10), tx(10_000, Plan.LUMP_SUM, facts), null, issues);
        assertThat(result.minimumAmount()).isEqualTo(1L); // 인테이크 유실적: 정수 원화의 양수 최소값.
        assertThat(result.recognizedAmount()).isEqualTo(10_000L);
        assertThat(result.period()).isNull(); // 수집 요약에 일반 거래 기준 사건의 근거가 없어 분리 보존.
    }

    @Test
    void unrelatedUnverifiedCategoryDoesNotBlockCoffeeButItsOwnTransactionRemainsUnknown() {
        var id = "lotte-p13949-a13949";
        var facts = ordinaryDomestic(id);
        var coffee = tx(10_000, Plan.LUMP_SUM, facts);
        assertThat(resolver.resolve(1, product(id, 10), coffee, null, issues).recognizedAmount()).isEqualTo(10_000L);
        var electric = new Transaction(coffee.amount(), coffee.plan(), coffee.installmentMonths(), coffee.occurredAt(),
                coffee.merchantId(), "ELECTRIC_UTILITY", facts);
        assertThat(resolver.resolve(1, product(id, 10), electric, null, issues).recognizedAmount()).isNull();
    }

    @Test
    void unregisteredOrDifferentIssuerProductDoesNotBorrowAnotherProductsRules() {
        var product = product("shinhan-cheoeum", 10);
        org.springframework.test.util.ReflectionTestUtils.setField(product, "issuerCode", "OTHER_ISSUER");
        var result = resolver.resolve(1, product, tx(10_000, Plan.LUMP_SUM, ordinaryDomestic("shinhan-cheoeum")), null, issues);
        assertThat(result.period()).isNull();
        assertThat(result.minimumAmount()).isNull();
        assertThat(result.recognizedAmount()).isNull();
        assertThat(issues).extracting(RecommendationDataIssue::code).contains("RECOGNITION_PRODUCT_NOT_IN_SOURCE");
    }

    @Test
    void wonderExplicitInstallmentMonthIsUsableEvenWhenItsOrdinaryMonthBasisIsMissing() {
        var id = "hana-wonder2-daily";
        var facts = ordinaryDomestic(id);
        var installment = resolver.resolve(1, product(id, 10), tx(50_000, Plan.INSTALLMENT, facts), null, issues);
        assertThat(installment.period()).isEqualTo(YearMonth.of(2026, 10));
        assertThat(installment.minimumAmount()).isEqualTo(400_000L);
        assertThat(installment.recognizedAmount()).isEqualTo(50_000L);
        assertThat(resolver.resolve(1, product(id, 10), tx(50_000, Plan.LUMP_SUM, facts), null, issues).period()).isNull();
    }

    private Transaction tx(long amount, Plan plan, Map<String, Boolean> facts) {
        return new Transaction(amount, plan, plan == Plan.LUMP_SUM ? 1 : 2,
                Instant.parse("2026-09-30T15:30:00Z"), 20L, "COFFEE_SHOP", facts);
    }
}
