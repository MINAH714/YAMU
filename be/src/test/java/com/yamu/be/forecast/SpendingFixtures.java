package com.yamu.be.forecast;

import java.nio.ByteBuffer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;

/**
 * 소비 분류·생활비 원천 테스트용 카드·가맹점·결제 행을 SQL로 바로 넣는다. 사용자·계좌는 {@code OwnershipFixtures}로 만든다.
 * 암호문·해시 칸은 형식만 맞춘 가짜 값이다.
 */
class SpendingFixtures {
    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");

    private final JdbcTemplate jdbc;

    SpendingFixtures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    long product(String cardType) {
        return insert("card_product", "card_product_id", Map.of("issuer_code", "TEST",
                "product_name", UUID.randomUUID().toString(), "card_type", cardType, "billing_cutoff_days", 14));
    }

    /** 한국 날짜 registeredOn 정오에 등록한 ACTIVE 카드. registeredOn이 null이면 등록 전(PENDING) 카드. */
    long card(long owner, long product, long account, LocalDate registeredOn) {
        Map<String, Object> fields = new HashMap<>(Map.of("user_id", owner, "card_product_id", product,
                "linked_account_id", account, "card_no_enc", new byte[] {1, 2}, "card_no_hash", hash(),
                "card_last4", "1234", "status", registeredOn == null ? "PENDING" : "ACTIVE"));
        if (registeredOn != null) {
            fields.put("registered_at", utc(registeredOn, 12));
        }
        return insert("user_card", "user_card_id", fields);
    }

    long merchant() {
        return insert("merchant", "merchant_id", Map.of("merchant_name", "테스트 가맹점",
                "brand_code", "SPENDING_" + UUID.randomUUID(), "merchant_category_code", "COFFEE_SHOP"));
    }

    /** 한국 날짜 day의 hour시에 승인된 일시불 YAMU 결제. */
    long approved(long owner, long card, long merchant, long amount, LocalDate day, int hour) {
        return payment(owner, card, merchant, amount, 1, utc(day, hour), "YAMU", "APPROVED");
    }

    /**
     * 승인된 결제 한 건. status가 CANCELED면 취소 시각도 넣는다.
     *
     * @param approvedAt UTC 승인 시각
     */
    long payment(long owner, long card, long merchant, long amount, int installmentMonths, LocalDateTime approvedAt,
            String source, String status) {
        long product = jdbc.queryForObject("SELECT card_product_id FROM user_card WHERE user_card_id = ?", Long.class,
                card);
        LocalDate month = approvedAt.atOffset(ZoneOffset.UTC).atZoneSameInstant(KOREA).toLocalDate().withDayOfMonth(1);
        Map<String, Object> fields = new HashMap<>(Map.ofEntries(Map.entry("user_id", owner),
                Map.entry("merchant_id", merchant), Map.entry("user_card_id", card),
                Map.entry("card_product_id", product), Map.entry("request_key", UUID.randomUUID().toString()),
                Map.entry("request_fingerprint", hash()), Map.entry("source", source), Map.entry("status", status),
                Map.entry("requested_amount", Math.max(amount, 1L)), Map.entry("approved_amount", amount),
                Map.entry("installment_months", installmentMonths), Map.entry("recognized_performance_amount", 0L),
                Map.entry("performance_month", month), Map.entry("requested_at", approvedAt),
                Map.entry("approved_at", approvedAt)));
        if (status.equals("CANCELED")) {
            fields.put("canceled_at", approvedAt.plusHours(1));
        }
        return insert("payment_transaction", "payment_transaction_id", fields);
    }

    String spendingClass(long payment) {
        return jdbc.queryForObject("SELECT spending_class FROM payment_transaction WHERE payment_transaction_id = ?",
                String.class, payment);
    }

    String spendingClassSource(long payment) {
        return jdbc.queryForObject(
                "SELECT spending_class_source FROM payment_transaction WHERE payment_transaction_id = ?",
                String.class, payment);
    }

    void classifyAs(long payment, String spendingClass, String source) {
        jdbc.update("UPDATE payment_transaction SET spending_class = ?, spending_class_source = ? "
                + "WHERE payment_transaction_id = ?", spendingClass, source, payment);
    }

    /** 한국 날짜 day의 hour시를 UTC로. */
    static LocalDateTime utc(LocalDate day, int hour) {
        return day.atTime(LocalTime.of(hour, 0)).atZone(KOREA).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    private long insert(String table, String idColumn, Map<String, Object> fields) {
        Map<String, Object> row = new HashMap<>(fields);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        row.put("created_at", now);
        row.put("updated_at", now);
        return new SimpleJdbcInsert(jdbc).withTableName(table).usingGeneratedKeyColumns(idColumn)
                .usingColumns(row.keySet().toArray(String[]::new))
                .executeAndReturnKey(row).longValue();
    }

    private static byte[] hash() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        return ByteBuffer.allocate(32).putLong(a.getMostSignificantBits()).putLong(a.getLeastSignificantBits())
                .putLong(b.getMostSignificantBits()).putLong(b.getLeastSignificantBits()).array();
    }
}
