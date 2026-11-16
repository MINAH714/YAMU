package com.yamu.be.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.yamu.be.card.domain.UserCard;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IbkPolicyRulesTest {
    final UserCard card = mock(UserCard.class);
    final LocalDate date = LocalDate.of(2026, 11, 3);
    final ArrayList<RecommendationDataIssue> issues = new ArrayList<>();

    IbkPolicyRulesTest() {
        when(card.getId()).thenReturn(1L);
        when(card.getIssuerRegisteredOn()).thenReturn(LocalDate.of(2026, 1, 1));
        when(card.getIbkContextMonth()).thenReturn(date.withDayOfMonth(1));
        when(card.getIbkSalaryQualified()).thenReturn(false);
        when(card.getIbkMilitaryQualified()).thenReturn(false);
    }

    boolean coverage(long previous, String brand, String category, Map<String, Boolean> facts) {
        var tx = new RecommendationInput.Transaction(10000, RecommendationInput.Plan.LUMP_SUM, 1,
                Instant.parse("2026-11-03T03:00:00Z"), 1L, category, facts);
        return IbkPolicyRules.coverage(card, date, previous, tx, brand, issues);
    }

    @Test void unsupportedNpayRequiresVerifiedNonNpayToExclude() {
        assertThat(coverage(250000,"GS25","CONVENIENCE_STORE",Map.of("REGION:DOMESTIC",true))).isFalse();
        assertThat(issues).extracting(RecommendationDataIssue::code).contains("IBK_UNSUPPORTED_NPAY");
        issues.clear();
        assertThat(coverage(250000,"GS25","CONVENIENCE_STORE",Map.of("REGION:DOMESTIC",true,"PAYMENT_METHOD:NPAY",false))).isTrue();
    }

    @Test void unavailableBenefitIsRelevantOnlyAtItsThresholdAndMerchant() {
        assertThat(coverage(79999,"CGV","MOVIE_THEATER",Map.of("REGION:DOMESTIC",true))).isTrue();
        assertThat(coverage(80000,"CGV","MOVIE_THEATER",Map.of("REGION:DOMESTIC",true))).isFalse();
        assertThat(issues).extracting(RecommendationDataIssue::code).contains("IBK_UNSUPPORTED_CGV");
    }

    @Test void staleMonthlyQualificationAndNewHolderAreNotEligibleDefaults() {
        when(card.getIbkContextMonth()).thenReturn(LocalDate.of(2026,10,1));
        when(card.getIssuerRegisteredOn()).thenReturn(LocalDate.of(2026,10,1));
        assertThat(coverage(0,"GS25","CONVENIENCE_STORE",Map.of("REGION:DOMESTIC",true))).isFalse();
        assertThat(issues).extracting(RecommendationDataIssue::code)
                .contains("IBK_MONTHLY_ELIGIBILITY_UNKNOWN","IBK_INTRODUCTORY_WAIVER_EXCLUDED");
    }

    @Test void unsupportedBasketServiceCannotDisappearBelowPerformanceThreshold() {
        assertThat(coverage(0,"EMART24","CONVENIENCE_STORE",Map.of("REGION:DOMESTIC",true))).isFalse();
        assertThat(issues).extracting(RecommendationDataIssue::code).contains("IBK_UNSUPPORTED_EMART24_BASKET");
    }
}
