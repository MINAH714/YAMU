package com.yamu.be.seed.qa;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yamu.be.global.calendar.KoreanBusinessDayCalendar;
import com.yamu.be.payment.billing.BillingCycle;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;

/** Current display bills only; imported historic SEED purchases do not trigger bank withdrawals. */
final class QaBillingFixtures {
    @FunctionalInterface interface Insert { long row(String sql, Object... values) throws Exception; }
    private final JdbcTemplate db;
    private final Insert insert;
    private final Clock clock;
    QaBillingFixtures(JdbcTemplate db, Insert insert, Clock clock) {
        this.db = db;
        this.insert = insert;
        this.clock = clock;
    }
    void create(long user, LocalDate base) throws Exception {
        KoreanBusinessDayCalendar calendar = new KoreanBusinessDayCalendar(year -> {
            try (var stream = getClass().getResourceAsStream("/holidays/" + year + ".json")) {
                if (stream == null) return Optional.empty();
                Set<LocalDate> dates = new HashSet<>();
                for (var row : new ObjectMapper().readTree(stream).path("holidays")) {
                    dates.add(LocalDate.parse(row.path("date").asText()));
                }
                return Optional.of(dates);
            } catch (Exception failure) {
                throw new IllegalStateException("Bundled holiday fixture unavailable");
            }
        });
        var payments = db.queryForList("""
                SELECT t.*,c.payment_day,c.linked_account_id,p.billing_cutoff_days
                FROM payment_transaction t JOIN user_card c USING(user_card_id)
                JOIN card_product p ON p.card_product_id=c.card_product_id
                WHERE t.user_id=? AND p.card_type='CREDIT' AND t.source='SEED'
                AND t.status IN ('APPROVED','CANCELED')
                AND (t.performance_month>=? OR t.installment_months>1)
                ORDER BY t.payment_transaction_id
                """, user, base.withDayOfMonth(1));
        for (var p : payments) {
            long payment = ((Number) p.get("payment_transaction_id")).longValue();
            long card = ((Number) p.get("user_card_id")).longValue();
            long account = ((Number) p.get("linked_account_id")).longValue();
            int count = ((Number) p.get("installment_months")).intValue();
            long total = ((Number) p.get("approved_amount")).longValue();
            BillingCycle cycle = BillingCycle.of(((Number) p.get("payment_day")).intValue(),
                    p.get("billing_cutoff_days") == null ? null : ((Number) p.get("billing_cutoff_days")).intValue());
            LocalDate date = ((Timestamp) p.get("approved_at")).toInstant().atZone(ZoneId.of("Asia/Seoul")).toLocalDate();
            YearMonth first = cycle.cycleFor(date).billingMonth();
            for (int n = 1; n <= count; n++) {
                var period = cycle.cycleOf(first.plusMonths(n - 1));
                List<Long> existing = db.queryForList(
                        "SELECT card_bill_id FROM card_bill WHERE user_card_id=? AND billing_month=?",
                        Long.class, card, period.billingMonth().atDay(1));
                Timestamp now = Timestamp.from(clock.instant());
                long bill = existing.isEmpty() ? insert.row("""
                        INSERT INTO card_bill(user_id,user_card_id,linked_account_id,billing_month,period_start,
                          period_end,withdrawal_date,status,created_at,updated_at)
                        VALUES (?,?,?,?,?,?,?,'ACCUMULATING',?,?)
                        """, user, card, account, period.billingMonth().atDay(1), period.periodStart(), period.periodEnd(),
                        calendar.businessDayOnOrAfter(period.paymentDate()), now, now) : existing.get(0);
                long principal = n == count ? total - total / count * (count - 1) : total / count;
                // Fees intentionally NULL: no invented interest-free policy for imported history.
                insert.row("""
                        INSERT INTO bill_item(payment_transaction_id,user_id,user_card_id,card_bill_id,
                          installment_no,billing_month,principal_amount,status,created_at,updated_at)
                        VALUES (?,?,?,?,?,?,?,?,?,?)
                        """, payment, user, card, bill, n, period.billingMonth().atDay(1), principal,
                        "CANCELED".equals(p.get("status")) ? "EXCLUDED" : "PENDING", now, now);
            }
        }
    }
}
