package com.yamu.be.seed;

import com.yamu.be.global.crypto.PersonalDataCipher;
import com.yamu.be.global.crypto.CipherField;
import com.yamu.be.global.crypto.LookupHasher;
import com.yamu.be.global.crypto.PinHasher;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.UUID;

/** 로컬 가상 사용자 전용. 금융망 API 호출·입금·출금·기기 인증을 수행하지 않는다. */
public final class SeedUserData {
    private static final String NAME = "야무테스터";
    private static final String PHONE = "01000000001";
    private static final String[] PRODUCTS = {"신한카드 Mr.Life", "현대카드 ZERO Edition3 (할인형)", "신한카드 처음"};
    private final Connection connection;
    private final PersonalDataCipher cipher;
    private final LookupHasher lookup;
    private final PinHasher pins;
    private final Clock clock;

    public SeedUserData(Connection connection, PersonalDataCipher cipher, LookupHasher lookup, PinHasher pins, Clock clock) {
        this.connection = connection;
        this.cipher = cipher;
        this.lookup = lookup;
        this.pins = pins;
        this.clock = clock;
    }

    public void load(String financialUserId, String financialUserKey, String bankCode, String accountNumber,
                     String pin, boolean reset) throws Exception {
        require(financialUserId != null && financialUserId.matches("[^\\s@]+@yamu\\.app"));
        require(financialUserKey != null && !financialUserKey.isBlank());
        require(accountNumber != null && accountNumber.matches("[0-9]{4,}"));
        require(pin != null && pin.matches("[0-9]{6}"));
        byte[] phoneHash = lookup.phone(PHONE);
        byte[] accountHash = lookup.account(bankCode, accountNumber);
        boolean originalAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            Long existing = existingUser(financialUserId, phoneHash);
            if (existing != null && !reset) {
                if (!hasCurrentCards(existing)) {
                    throw new ResetRequiredException();
                }
                connection.commit();
                return;
            }
            if (existing != null) {
                deleteUserData(existing);
            }
            LocalDate today = LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));
            LocalDate previousMonth = YearMonth.from(today).minusMonths(1).atDay(1);
            LocalDate currentMonth = YearMonth.from(today).atDay(1);
            Timestamp now = Timestamp.from(clock.instant());
            Timestamp registered = Timestamp.from(previousMonth.minusMonths(2).atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant());
            long user = insert("""
                    INSERT INTO app_user (name,birth_date,phone_enc,phone_hash,pin_hash,pin_updated_at,
                        identity_verified_at,ssafy_member_id,ssafy_user_key,status,notification_settings,created_at,updated_at)
                    VALUES (?,?,?,?,?,?,?,?,?,'ACTIVE',?, ?,?)
                    """, NAME, LocalDate.of(1995, 1, 1), cipher.encrypt(PHONE, CipherField.APP_USER_PHONE), phoneHash, pins.hash(pin), registered,
                    registered, financialUserId, cipher.encrypt(financialUserKey, CipherField.APP_USER_SSAFY_USER_KEY),
                    "{\"performanceEnabled\":true,\"balanceRiskEnabled\":true,\"transactionEnabled\":true}", now, now);
            long account = insert("""
                    INSERT INTO linked_account (user_id,bank_code,account_no_enc,account_no_hash,account_name,
                        account_alias,account_last4,status,connected_at,created_at,updated_at)
                    VALUES (?,?,?,?,?,?,?,'CONNECTED',?,?,?)
                    """, user, bankCode, cipher.encrypt(accountNumber, CipherField.LINKED_ACCOUNT_NO), accountHash, "YAMU 테스트 입출금", "테스트 결제 계좌",
                    last4(accountNumber), registered, now, now);
            String[] products = PRODUCTS;
            for (int index = 0; index < products.length; index++) {
                long product = requiredId("SELECT card_product_id FROM card_product WHERE product_name=? AND card_type='CREDIT'", products[index]);
                // 모의 번호이며 금융망·실제 카드사에 전송하지 않는다.
                String cardNumber = "941000000000000" + (index + 1);
                long card = insert("""
                        INSERT INTO user_card (user_id,card_product_id,linked_account_id,card_no_enc,card_no_hash,
                            card_last4,card_alias,status,issuer_status,registered_at,created_at,updated_at)
                        VALUES (?,?,?,?,?,?,?,'ACTIVE','NORMAL',?,?,?)
                        """, user, product, account, cipher.encrypt(cardNumber, CipherField.USER_CARD_NO), lookup.card(cardNumber), last4(cardNumber),
                        products[index], registered, now, now);
                long performance = index == 1 ? 0 : 400000;
                execute("INSERT INTO card_monthly_performance (user_card_id,performance_month,recognized_amount,created_at,updated_at) VALUES (?,?,?,?,?)",
                        card, previousMonth, performance, now, now);
                execute("INSERT INTO card_monthly_performance (user_card_id,performance_month,recognized_amount,created_at,updated_at) VALUES (?,?,?,?,?)",
                        card, currentMonth, 0, now, now);
                execute("""
                        INSERT INTO monthly_limit_usage (user_card_id,card_product_id,limit_group_id,usage_month,used_value,created_at,updated_at)
                        SELECT ?,?,limit_group_id,?,0,?,? FROM limit_group
                        WHERE card_product_id=? AND period_type='MONTHLY' AND policy_version=1
                        """, card, product, currentMonth, now, now, product);
                if (performance > 0) {
                    historicalPayment(user, card, product, financialUserId, products[index], previousMonth, now);
                }
            }
            connection.commit();
        } catch (Exception failure) {
            connection.rollback();
            throw failure;
        } finally {
            connection.setAutoCommit(originalAutoCommit);
        }
    }

    private Long existingUser(String member, byte[] phoneHash) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT user_id,name,phone_hash FROM app_user WHERE ssafy_member_id=? FOR UPDATE")) {
            statement.setString(1, member);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                if (!NAME.equals(rows.getString(2)) || !Arrays.equals(phoneHash, rows.getBytes(3))) {
                    throw new IllegalStateException("Existing financial user is not this local fixture; refusing to modify it");
                }
                return rows.getLong(1);
            }
        }
    }

    private boolean hasCurrentCards(long user) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*) AS total,
                    SUM(p.product_name IN (?,?,?)) AS current_cards,
                    COUNT(DISTINCT p.product_name) AS distinct_products
                FROM user_card c JOIN card_product p USING (card_product_id) WHERE c.user_id=?
                """)) {
            bind(statement, new Object[]{PRODUCTS[0], PRODUCTS[1], PRODUCTS[2], user});
            try (var rows = statement.executeQuery()) {
                return rows.next() && rows.getInt("total") == 3 && rows.getInt("current_cards") == 3
                        && rows.getInt("distinct_products") == 3;
            }
        }
    }

    static final class ResetRequiredException extends IllegalStateException {
        ResetRequiredException() { super("Local seed card composition changed; explicit reset required"); }
    }

    private void historicalPayment(long user, long card, long product, String member, String productName,
                                   LocalDate month, Timestamp now) throws Exception {
        long merchant = requiredId("SELECT merchant_id FROM merchant WHERE brand_code=?", "YAMU_TEST_RESTAURANT");
        Timestamp approved = Timestamp.from(month.withDayOfMonth(15).atTime(12, 0)
                .atZone(ZoneId.of("Asia/Seoul")).toInstant());
        String request = UUID.nameUUIDFromBytes((member + ":" + productName + ":" + month).getBytes(StandardCharsets.UTF_8)).toString();
        byte[] fingerprint = MessageDigest.getInstance("SHA-256").digest(
                (request + ":LOCAL_SEED:YAMU_TEST_RESTAURANT:400000:OFFLINE:GENERAL:1").getBytes(StandardCharsets.UTF_8));
        execute("""
                INSERT INTO payment_transaction (user_id,user_card_id,card_product_id,merchant_id,payment_channel,
                    facility_type,merchant_context_snapshot,request_key,request_fingerprint,source,status,requested_amount,
                    approved_amount,installment_months,recognized_performance_amount,performance_basis_amount,performance_month,
                    requested_at,approved_at,created_at,updated_at)
                VALUES (?,?,?,?,'OFFLINE','GENERAL',?,?,?,'SEED','APPROVED',400000,400000,1,400000,NULL,?,?,?,?,?)
                """, user, card, product, merchant,
                "{\"brandCode\":\"YAMU_TEST_RESTAURANT\",\"merchantCategoryCode\":\"GENERAL_RESTAURANT\",\"transactionKind\":\"PURCHASE\",\"source\":\"LOCAL_SEED\"}",
                request, fingerprint, month, approved, approved, now, now);
    }

    private void deleteUserData(long user) throws SQLException {
        // Only this fixture owner is reset. FK checks remain enabled throughout.
        execute("DELETE FROM cashflow_payment_match WHERE user_id=?", user);
        execute("DELETE FROM cashflow_occurrence WHERE user_id=?", user);
        execute("UPDATE cashflow_item SET previous_cashflow_item_id=NULL WHERE user_id=?", user);
        execute("DELETE FROM cashflow_item WHERE user_id=?", user);
        execute("DELETE FROM notification WHERE user_id=?", user);
        execute("DELETE FROM refund WHERE user_id=?", user);
        execute("DELETE FROM bill_item WHERE user_id=?", user);
        execute("DELETE FROM card_bill WHERE user_id=?", user);
        execute("DELETE b FROM payment_benefit b JOIN payment_transaction p USING (payment_transaction_id) WHERE p.user_id=?", user);
        execute("DELETE u FROM payment_limit_usage u JOIN payment_transaction p USING (payment_transaction_id) WHERE p.user_id=?", user);
        execute("DELETE FROM payment_transaction WHERE user_id=?", user);
        execute("DELETE u FROM monthly_limit_usage u JOIN user_card c USING (user_card_id) WHERE c.user_id=?", user);
        execute("DELETE p FROM card_monthly_performance p JOIN user_card c USING (user_card_id) WHERE c.user_id=?", user);
        execute("DELETE FROM user_card WHERE user_id=?", user);
        execute("DELETE FROM linked_account WHERE user_id=?", user);
        execute("DELETE FROM step_up_audit_log WHERE user_id=?", user);
        execute("DELETE e FROM user_device_event e JOIN user_device d USING (user_device_id) WHERE d.user_id=?", user);
        execute("DELETE FROM user_device WHERE user_id=?", user);
        execute("DELETE FROM app_user WHERE user_id=?", user);
    }

    private long requiredId(String sql, Object parameter) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, parameter);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new IllegalStateException("Required seed reference is missing");
                }
                long id = rows.getLong(1);
                if (rows.next()) {
                    throw new IllegalStateException("Required seed reference is ambiguous");
                }
                return id;
            }
        }
    }

    private long insert(String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(statement, values);
            statement.executeUpdate();
            try (var keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("Seed insert did not return an ID");
                }
                return keys.getLong(1);
            }
        }
    }

    private void execute(String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            statement.executeUpdate();
        }
    }

    private static void bind(PreparedStatement statement, Object[] values) throws SQLException {
        for (int index = 0; index < values.length; index++) {
            statement.setObject(index + 1, values[index]);
        }
    }

    private static String last4(String value) { return value.substring(value.length() - 4); }
    private static void require(boolean valid) {
        if (!valid) { throw new IllegalArgumentException("Required local fixture settings are invalid"); }
    }
}
