package com.yamu.be.payment.billing;

import java.nio.ByteBuffer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;

/**
 * 청구서 조회 테스트용 행을 SQL로 바로 넣는다. 청구 확정·출금 배치(Task 5.4.2·5.4.3)가 아직 없어 확정·출금 완료·미납 청구서는
 * 이렇게만 만들 수 있다. 상태별로 DB 제약(ck_bill_confirmed·ck_bill_paid)이 요구하는 칸을 함께 채운다.
 */
public class BillFixtures {

    private final JdbcTemplate jdbc;

    public BillFixtures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long merchant(String name) {
        return insert("merchant", "merchant_id", Map.of("merchant_name", name, "brand_code", "BILL_" + UUID.randomUUID(),
                "merchant_category_code", "COFFEE_SHOP"));
    }

    /** 승인된 신용 결제. months: 일시불 1, 할부는 개월 수 */
    public long payment(long user, long card, long merchant, long amount, int months, LocalDateTime approvedAt) {
        long product = jdbc.queryForObject("SELECT card_product_id FROM user_card WHERE user_card_id = ?", Long.class,
                card);
        return insert("payment_transaction", "payment_transaction_id", Map.ofEntries(Map.entry("user_id", user),
                Map.entry("merchant_id", merchant), Map.entry("user_card_id", card),
                Map.entry("card_product_id", product), Map.entry("request_key", UUID.randomUUID().toString()),
                Map.entry("request_fingerprint", randomHash()), Map.entry("source", "YAMU"),
                Map.entry("status", "APPROVED"), Map.entry("requested_amount", amount),
                Map.entry("approved_amount", amount), Map.entry("installment_months", months),
                Map.entry("recognized_performance_amount", 0L),
                Map.entry("performance_month", approvedAt.toLocalDate().withDayOfMonth(1)),
                Map.entry("requested_at", approvedAt), Map.entry("approved_at", approvedAt)));
    }

    /** 진행 중 청구서. 확정 전이라 출금 계좌 칸은 비어 있다. 이용기간은 청구 월 전달 2일 ~ 청구 월 1일(결제일 15일, 마감 14일). */
    public long accumulating(long user, long card, YearMonth month, LocalDate withdrawalDate) {
        return insert("card_bill", "card_bill_id", base(user, card, month, withdrawalDate, "ACCUMULATING"));
    }

    /** 청구 확정(출금 대기). account는 확정 당시 출금 계좌 */
    public long confirmed(long user, long card, YearMonth month, LocalDate withdrawalDate, long account, long amount) {
        Map<String, Object> row = base(user, card, month, withdrawalDate, "CONFIRMED");
        confirm(row, account, amount);
        return insert("card_bill", "card_bill_id", row);
    }

    /** 출금 완료 */
    public long paid(long user, long card, YearMonth month, LocalDate withdrawalDate, long account, long amount,
            String finTransactionNo) {
        Map<String, Object> row = base(user, card, month, withdrawalDate, "PAID");
        confirm(row, account, amount);
        row.put("paid_amount", amount);
        row.put("paid_at", withdrawalDate.atTime(0, 0, 12));
        row.put("fin_transaction_no", finTransactionNo);
        return insert("card_bill", "card_bill_id", row);
    }

    /** 출금 실패·미납. waived는 취소로 납부 대상에서 뺀 미납 원금. 남은 미납액이 0이면 CANCELED로 둔다 */
    public long unpaid(long user, long card, YearMonth month, LocalDate withdrawalDate, long account, long amount,
            long waived) {
        Map<String, Object> row = base(user, card, month, withdrawalDate, waived == amount ? "CANCELED" : "UNPAID");
        confirm(row, account, amount);
        row.put("canceled_unpaid_principal", waived);
        row.put("failed_at", withdrawalDate.atTime(0, 0, 30));
        return insert("card_bill", "card_bill_id", row);
    }

    /** 청구 회차. 무이자라 수수료 0, 청구액 = 원금 */
    public long item(long payment, long user, long card, long bill, int installmentNo, YearMonth month, long principal,
            String status) {
        return insert("bill_item", "bill_item_id", Map.of("payment_transaction_id", payment, "user_id", user,
                "user_card_id", card, "card_bill_id", bill, "installment_no", installmentNo,
                "billing_month", month.atDay(1), "principal_amount", principal, "fee_amount", 0L,
                "billed_amount", principal, "status", status));
    }

    public long refund(long payment, long item, long user, long account, long amount, String status) {
        Map<String, Object> row = new HashMap<>(Map.of("payment_transaction_id", payment, "bill_item_id", item,
                "user_id", user, "linked_account_id", account, "request_key", UUID.randomUUID().toString(),
                "principal_amount", amount, "fee_amount", 0L, "refund_amount", amount, "status", status));
        if ("COMPLETED".equals(status)) {
            row.put("completed_at", now());
        }
        return insert("refund", "refund_id", row);
    }

    private static Map<String, Object> base(long user, long card, YearMonth month, LocalDate withdrawalDate,
            String status) {
        // 청구서는 이용기간의 첫 승인 때 생기므로 생성 시각을 이용기간 시작으로 둔다(상세의 상태 이력 순서가 실제와 같게)
        return new HashMap<>(Map.of("user_id", user, "user_card_id", card, "billing_month", month.atDay(1),
                "period_start", month.minusMonths(1).atDay(2), "period_end", month.atDay(1),
                "withdrawal_date", withdrawalDate, "status", status,
                "created_at", month.minusMonths(1).atDay(2).atStartOfDay()));
    }

    private static void confirm(Map<String, Object> row, long account, long amount) {
        LocalDate periodEnd = (LocalDate) row.get("period_end");
        row.put("linked_account_id", account);
        row.put("confirmed_amount", amount);
        row.put("confirmed_at", periodEnd.atTime(15, 30));
    }

    private long insert(String table, String idColumn, Map<String, Object> fields) {
        Map<String, Object> row = new HashMap<>(fields);
        row.putIfAbsent("created_at", now());
        row.putIfAbsent("updated_at", now());
        return new SimpleJdbcInsert(jdbc).withTableName(table).usingGeneratedKeyColumns(idColumn)
                .usingColumns(row.keySet().toArray(String[]::new))
                .executeAndReturnKey(row).longValue();
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private static byte[] randomHash() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        return ByteBuffer.allocate(32).putLong(a.getMostSignificantBits()).putLong(a.getLeastSignificantBits())
                .putLong(b.getMostSignificantBits()).putLong(b.getLeastSignificantBits()).array();
    }
}
