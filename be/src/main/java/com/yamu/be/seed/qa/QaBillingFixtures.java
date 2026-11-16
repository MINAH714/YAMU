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

/** Executable QA bills bound to the persona's dedicated connected accounts. */
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
        create(user, base, false);
    }
    void create(long user, LocalDate base, boolean life) throws Exception {
        KoreanBusinessDayCalendar calendar = life ? QaLifeScenario.calendar() : new KoreanBusinessDayCalendar(year -> {
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
                WHERE t.user_id=? AND p.card_type='CREDIT' AND (t.source='SEED' OR JSON_UNQUOTE(JSON_EXTRACT(t.merchant_context_snapshot,'$.source'))='QA_LIFE_550')
                AND t.status IN ('APPROVED','CANCELED')
                AND (? OR t.performance_month>=? OR t.installment_months>1 OR t.status='CANCELED')
                ORDER BY t.payment_transaction_id
                """, user, life, base.withDayOfMonth(1));
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
                boolean canceled = "CANCELED".equals(p.get("status"));
                // Match BillItem.pending: the current engine starts with zero fees and principal billed.
                long item = insert.row("""
                        INSERT INTO bill_item(payment_transaction_id,user_id,user_card_id,card_bill_id,
                          installment_no,billing_month,principal_amount,fee_amount,billed_amount,status,created_at,updated_at)
                        VALUES (?,?,?,?,?,?,?,0,?,?,?,?)
                        """, payment, user, card, bill, n, period.billingMonth().atDay(1), principal,
                        principal, canceled ? "REFUND_PENDING" : "PENDING", now, now);
                if (canceled) {
                    QaSeedSpec.require(existing.isEmpty() && count == 1, "Canceled fixture needs a separate bill");
                    Timestamp confirmed = Timestamp.from(period.periodEnd().plusDays(1)
                            .atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant());
                    Timestamp canceledAt = Timestamp.from(confirmed.toInstant().plusSeconds(3600));
                    QaSeedSpec.require(!canceledAt.toInstant().isAfter(clock.instant()), "Future canceled fixture");
                    db.update("""
                            UPDATE card_bill SET status='CONFIRMED',confirmed_amount=?,confirmed_at=?
                            WHERE card_bill_id=?
                            """, principal, confirmed, bill);
                    db.update("""
                            UPDATE payment_transaction SET canceled_at=?,performance_reversed_at=?
                            WHERE payment_transaction_id=?
                            """, canceledAt, canceledAt, payment);
                    insert.row("""
                            INSERT INTO refund(payment_transaction_id,bill_item_id,user_id,linked_account_id,
                              request_key,principal_amount,fee_amount,refund_amount,status,created_at,updated_at)
                            VALUES (?,?,?,?,UUID(),?,0,?,'PENDING',?,?)
                            """, payment, item, user, account, principal, principal, canceledAt, now);
                }
            }
        }
        if (life) {
            // LIFE purchases are all lump-sum: each billing discount belongs to its single installment.
            db.update("""
                INSERT INTO bill_item_discount(card_bill_id,bill_item_id,payment_benefit_id,amount,created_at)
                SELECT b.card_bill_id,i.bill_item_id,pb.payment_benefit_id,pb.krw_amount,
                  DATE_SUB(TIMESTAMP(DATE_ADD(b.period_end,INTERVAL 1 DAY)),INTERVAL 9 HOUR)
                FROM card_bill b JOIN bill_item i USING(card_bill_id)
                JOIN payment_benefit pb ON pb.payment_transaction_id=i.payment_transaction_id
                JOIN card_benefit cb USING(card_benefit_id)
                WHERE b.user_id=? AND b.period_end<? AND cb.benefit_type='DISCOUNT'
                  AND cb.application_timing='AT_BILLING' AND pb.krw_amount>0
                """,user,base);
            // Historical settled fixtures have no invented bank identifiers. Today's due bill stays unpaid.
            db.update("""
                UPDATE card_bill b SET confirmed_amount=(SELECT SUM(i.billed_amount) FROM bill_item i WHERE i.card_bill_id=b.card_bill_id)
                  -COALESCE((SELECT SUM(d.amount) FROM bill_item_discount d WHERE d.card_bill_id=b.card_bill_id),0),
                  confirmed_at=DATE_SUB(TIMESTAMP(DATE_ADD(b.period_end,INTERVAL 1 DAY)),INTERVAL 9 HOUR),
                  status=IF(b.withdrawal_date<?,'PAID','CONFIRMED')
                WHERE b.user_id=? AND b.period_end<?
                """,base,user,base);
            db.update("""
                UPDATE card_bill SET paid_amount=confirmed_amount,
                  paid_at=DATE_SUB(TIMESTAMP(withdrawal_date),INTERVAL 9 HOUR)
                WHERE user_id=? AND status='PAID'
                """,user);
            db.update("""
                UPDATE bill_item i JOIN card_bill b USING(card_bill_id)
                SET i.status=IF(b.status='PAID','PAID','BILLED')
                WHERE b.user_id=? AND b.status IN ('PAID','CONFIRMED')
                """,user);
        }
    }
}
