package com.yamu.be.seed.qa;

import static org.assertj.core.api.Assertions.*;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Test;

class QaHistoryPlanTest {
    @Test void emptyReallyHasNoTransactions() {
        assertThat(QaHistoryPlan.build(QaSeedSpec.Scenario.EMPTY, LocalDate.of(2026,11,12))).isEmpty();
    }
    @Test void deterministicSixMonthsAndNoFutureDatesAcrossYearLeapAndMonthBoundaries() {
        for (LocalDate base : List.of(LocalDate.of(2026,1,1), LocalDate.of(2028,3,1), LocalDate.of(2026,11,12))) {
            var plan = QaHistoryPlan.build(QaSeedSpec.Scenario.RICH, base);
            assertThat(plan).isEqualTo(QaHistoryPlan.build(QaSeedSpec.Scenario.RICH, base));
            assertThat(plan).allSatisfy(p -> {
                assertThat(p.date()).isBeforeOrEqualTo(base);
                assertThat(p.amount()).isPositive();
            });
            assertThat(plan.stream().filter(p -> p.card()==0).map(p -> YearMonth.from(p.date())).distinct().count())
                    .isGreaterThanOrEqualTo(6);
        }
    }
    @Test void boundaryHasExactThresholdAmountsAndExhaustedUtility() {
        LocalDate base = LocalDate.of(2026,11,12);
        var plan = QaHistoryPlan.build(QaSeedSpec.Scenario.BOUNDARY, base);
        assertThat(plan.stream().filter(p -> p.card()==1 && p.date().getMonthValue()==10)
                .mapToLong(QaHistoryPlan.Payment::amount).sum()).isEqualTo(299999);
        assertThat(plan.stream().filter(p -> p.card()==2 && p.date().getMonthValue()==10)
                .mapToLong(QaHistoryPlan.Payment::amount).sum()).isEqualTo(300000);
        assertThat(plan.stream().mapToLong(QaHistoryPlan.Payment::utilityDiscount).sum()).isEqualTo(3000);
    }
    @Test void statesIncludeCanceledDeclinedAndInstallments() {
        var plan = QaHistoryPlan.build(QaSeedSpec.Scenario.STATES, LocalDate.of(2026,11,12));
        assertThat(plan).extracting(QaHistoryPlan.Payment::status).contains("APPROVED","CANCELED","DECLINED");
        assertThat(plan).anyMatch(p -> p.installments()==3 && p.amount()==900000);
    }
}
