package com.yamu.be.cashflow;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;

/**
 * 예정 항목 테스트용 행을 SQL로 바로 넣는다. 등록 API가 막는 지난 날짜 규칙이나,
 * 결제 기능이 아직 만들지 않는 실제 결제 연결처럼 API로 만들 수 없는 상태를 준비할 때 쓴다.
 */
class CashflowFixtures {

    private final JdbcTemplate jdbc;

    CashflowFixtures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 계좌 지출 규칙(금액 10000)과 등록 때처럼 첫 회차 행을 넣고 규칙 ID를 돌려준다. */
    long rule(long owner, long account, LocalDate first, String recurrence, LocalDate end) {
        Map<String, Object> fields = new HashMap<>(Map.of("user_id", owner, "linked_account_id", account,
                "title", "헬스장", "direction", "EXPENSE", "amount", 10000L, "scheduled_date", first,
                "recurrence_type", recurrence, "status", "CONFIRMED"));
        fields.put("recurrence_end_date", end);
        long rule = insert("cashflow_item", "cashflow_item_id", fields);
        occurrence(owner, account, rule, first, first, false);
        return rule;
    }

    /** 회차 행. overridden이면 이번 회차만 고친 회차(금액 5000)다. */
    long occurrence(long owner, long account, long rule, LocalDate occurrenceDate, LocalDate scheduledDate,
            boolean overridden) {
        return insert("cashflow_occurrence", "cashflow_occurrence_id", Map.of("cashflow_item_id", rule,
                "user_id", owner, "occurrence_date", occurrenceDate, "scheduled_date", scheduledDate,
                "title", "헬스장", "direction", "EXPENSE", "amount", overridden ? 5000L : 10000L,
                "linked_account_id", account, "status", "CONFIRMED", "is_overridden", overridden));
    }

    /** 실제 결제를 하나 만들어 회차와 연결한다. */
    void paymentMatch(long owner, long occurrence) {
        long merchant = insert("merchant", "merchant_id", Map.of("merchant_name", "테스트 헬스장",
                "brand_code", UUID.randomUUID().toString(), "merchant_category_code", "SPORTS"));
        long payment = insert("payment_transaction", "payment_transaction_id", Map.of("user_id", owner,
                "merchant_id", merchant, "request_key", UUID.randomUUID().toString(),
                "request_fingerprint", new byte[32], "source", "YAMU", "status", "REQUESTED",
                "requested_amount", 89000L, "requested_at", now()));
        Map<String, Object> match = Map.of("user_id", owner, "cashflow_occurrence_id", occurrence,
                "payment_transaction_id", payment, "matched_at", now(), "created_at", now());
        new SimpleJdbcInsert(jdbc).withTableName("cashflow_payment_match")
                .usingColumns(match.keySet().toArray(String[]::new)).execute(match);
    }

    /** 카드 지정 예정 지출 규칙과 첫 회차 행을 넣고 규칙 ID를 돌려준다. category는 null이면 업종 없음이다. */
    long cardRule(long owner, long userCard, LocalDate first, String recurrence, long amount, String category) {
        Map<String, Object> fields = new HashMap<>(Map.of("user_id", owner, "user_card_id", userCard,
                "title", "헬스장", "direction", "EXPENSE", "amount", amount, "scheduled_date", first,
                "recurrence_type", recurrence, "status", "CONFIRMED"));
        fields.put("merchant_category_code", category);
        long rule = insert("cashflow_item", "cashflow_item_id", fields);
        Map<String, Object> row = new HashMap<>(Map.of("cashflow_item_id", rule, "user_id", owner,
                "occurrence_date", first, "scheduled_date", first, "title", "헬스장", "direction", "EXPENSE",
                "amount", amount, "user_card_id", userCard, "status", "CONFIRMED", "is_overridden", false));
        row.put("merchant_category_code", category);
        insert("cashflow_occurrence", "cashflow_occurrence_id", row);
        return rule;
    }

    long merchant(String category) {
        return insert("merchant", "merchant_id", Map.of("merchant_name", "테스트 가맹점",
                "brand_code", UUID.randomUUID().toString(), "merchant_category_code", category));
    }

    /** 한국 날짜 day 정오에 승인된 일시불 결제. status가 CANCELED면 취소 시각도 넣는다. */
    long payment(long owner, long userCard, long merchant, long amount, LocalDate day, String source,
            String status) {
        long product = jdbc.queryForObject("SELECT card_product_id FROM user_card WHERE user_card_id = ?", Long.class,
                userCard);
        LocalDateTime approvedAt = day.atTime(12, 0).atZone(java.time.ZoneId.of("Asia/Seoul"))
                .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        Map<String, Object> fields = new HashMap<>(Map.ofEntries(Map.entry("user_id", owner),
                Map.entry("merchant_id", merchant), Map.entry("user_card_id", userCard),
                Map.entry("card_product_id", product), Map.entry("request_key", UUID.randomUUID().toString()),
                Map.entry("request_fingerprint", new byte[32]), Map.entry("source", source),
                Map.entry("status", status), Map.entry("requested_amount", amount),
                Map.entry("approved_amount", amount), Map.entry("installment_months", 1),
                Map.entry("recognized_performance_amount", 0L), Map.entry("performance_month", day.withDayOfMonth(1)),
                Map.entry("requested_at", approvedAt), Map.entry("approved_at", approvedAt)));
        if (status.equals("CANCELED")) {
            fields.put("canceled_at", approvedAt.plusHours(1));
        }
        return insert("payment_transaction", "payment_transaction_id", fields);
    }

    private long insert(String table, String idColumn, Map<String, Object> fields) {
        Map<String, Object> row = new HashMap<>(fields);
        row.put("created_at", now());
        row.put("updated_at", now());
        return new SimpleJdbcInsert(jdbc).withTableName(table).usingGeneratedKeyColumns(idColumn)
                .usingColumns(row.keySet().toArray(String[]::new)).executeAndReturnKey(row).longValue();
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}
