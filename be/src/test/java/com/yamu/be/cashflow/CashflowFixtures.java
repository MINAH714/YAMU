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
