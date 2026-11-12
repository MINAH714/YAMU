package com.yamu.be.seed.qa;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/** Deterministic display fixtures. Not a second recommendation/benefit engine (Task 441). */
public final class QaHistoryPlan {
    private QaHistoryPlan() {}
    public record Payment(int card, LocalDate date, int slot, String brand, long amount,
                          String status, int installments, long utilityDiscount) {}
    public static List<Payment> build(QaSeedSpec.Scenario scenario, LocalDate base) {
        if (scenario == QaSeedSpec.Scenario.EMPTY) return List.of();
        List<Payment> rows = new ArrayList<>();
        LocalDate first = base.withDayOfMonth(1).minusMonths(6);
        String[] brands = {"STARBUCKS", "GS25", "YAMU_TEST_RESTAURANT", "EMART", "COUPANG"};
        for (LocalDate date = first; date.isBefore(base); date = date.plusDays(1)) {
            for (int slot = 0; slot < 3; slot++) {
                int index = Math.floorMod(date.getDayOfYear() + slot, brands.length);
                long amount = 3500 + index * 1700L + Math.floorMod(date.toEpochDay() + slot, 13) * 10;
                rows.add(new Payment(0, date, slot, brands[index], amount, "APPROVED", 1, 0));
            }
        }
        for (int months = 6; months >= 1; months--) {
            LocalDate date = YearMonth.from(base).minusMonths(months).atDay(10);
            rows.add(new Payment(1, date, 0, "COUPANG",
                    scenario == QaSeedSpec.Scenario.BOUNDARY ? 299999 : 400000, "APPROVED", 1, 0));
            rows.add(new Payment(2, date, 0, "EMART",
                    scenario == QaSeedSpec.Scenario.BOUNDARY ? 300000 : 400000, "APPROVED", 1, 0));
        }
        // Midnight KST on the first day is always <= the non-future reference date,
        // including a run on the first of a month. No future approved transaction.
        long discount = scenario == QaSeedSpec.Scenario.BOUNDARY ? 3000 : 1500;
        rows.add(new Payment(2, base.withDayOfMonth(1), 4, "SKT", discount * 10,
                "APPROVED", 1, discount));
        if (scenario == QaSeedSpec.Scenario.STATES) {
            rows.add(new Payment(2, base.minusDays(1), 5, "COUPANG", 900000, "APPROVED", 3, 0));
            rows.add(new Payment(2, base.minusDays(1), 6, "GS25", 12000, "CANCELED", 1, 0));
            rows.add(new Payment(2, base.minusDays(1), 7, "GS25", 8000, "DECLINED", 1, 0));
        }
        return List.copyOf(rows);
    }
}
