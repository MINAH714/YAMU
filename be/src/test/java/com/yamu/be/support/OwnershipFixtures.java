package com.yamu.be.support;

import java.nio.ByteBuffer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;

/**
 * 계좌·카드 소유 확인 테스트용 데이터를 SQL로 바로 넣는다. 가입·계좌 연결·카드 등록 API가 아직 없어서 엔티티 대신 행을 만든다.
 * 암호문·해시 칸은 형식만 맞춘 가짜 값이다.
 */
public class OwnershipFixtures {

    private final JdbcTemplate jdbc;

    public OwnershipFixtures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long user() {
        return insert("app_user", "user_id", Map.of("name", "lookup-test",
                "birth_date", LocalDate.of(2000, 1, 1), "phone_hash", randomHash()));
    }

    /** 연결된(CONNECTED) 계좌. */
    public long account(long owner) {
        return insert("linked_account", "linked_account_id", Map.of("user_id", owner, "bank_code", "088",
                "account_no_enc", new byte[] {1, 2}, "account_no_hash", randomHash(), "account_last4", "5934",
                "connected_at", now()));
    }

    public long product() {
        return insert("card_product", "card_product_id", Map.of("issuer_code", "TEST",
                "product_name", UUID.randomUUID().toString(), "card_type", "CREDIT", "billing_cutoff_days", 14));
    }

    /** issuer_status는 넣지 않는다(DB 기본값 NORMAL). */
    public long card(long owner, long product, Long account, String status) {
        Map<String, Object> fields = new HashMap<>(Map.of("user_id", owner, "card_product_id", product,
                "card_no_enc", new byte[] {1, 2}, "card_no_hash", randomHash(), "card_last4", "1234",
                "status", status));
        if (account != null) {
            fields.put("linked_account_id", account);
        }
        return insert("user_card", "user_card_id", fields);
    }

    private long insert(String table, String idColumn, Map<String, Object> fields) {
        Map<String, Object> row = new HashMap<>(fields);
        row.put("created_at", now());
        row.put("updated_at", now());
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
