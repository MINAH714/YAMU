package com.yamu.be.global.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yamu.be.support.MySqlTestSupport;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

/** 실제 MySQL에서 Flyway와 업무 핵심 제약을 검증한다. 업무 서비스 동시성 검증은 별도다. */
@Transactional
// The inherited container restarts between test classes. Do not reuse another class's DataSource.
@TestPropertySource(properties = "spring.application.name=yamu-schema-test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class InitialSchemaMigrationTest extends MySqlTestSupport {

    private static final LocalDate MONTH = LocalDate.of(2026, 10, 1);
    private static final List<String> TABLES = List.of(
            "app_user", "linked_account", "card_product", "user_card", "card_benefit",
            "benefit_target", "merchant", "limit_group", "benefit_limit_link", "tier_limit",
            "payment_transaction", "payment_benefit", "payment_limit_usage",
            "card_monthly_performance", "monthly_limit_usage", "card_bill", "bill_item",
            "refund", "cashflow_item", "notification", "user_device", "user_device_event",
            "step_up_audit_log");

    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;

    @Test
    void createsAllBusinessTablesAndDoesNotReapplyTheMigration() {
        List<String> actual = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()",
                String.class);
        assertThat(actual).containsAll(TABLES);
        assertThat(actual).doesNotContain("payment_limit_impact");
        assertThat(actual.stream().filter(TABLES::contains).count()).isEqualTo(23);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM flyway_schema_history
                WHERE version = '20261026.1' AND success = 1
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM flyway_schema_history
                WHERE version = '20261027.1' AND success = 1
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForMap("""
                SELECT column_type, is_nullable, column_comment FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'app_user'
                  AND column_name = 'ssafy_member_id'
                """)).containsEntry("COLUMN_TYPE", "varchar(255)")
                .containsEntry("IS_NULLABLE", "YES")
                .containsEntry("COLUMN_COMMENT",
                        "서버 생성 금융망 userId; y{랜덤 9자리}@yamu.app(19자, SSAFY 40자 제한), 최초 생성 후 재사용, 메일 인증 없음");
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }

    @Test
    void storesUtcEventTimestampsAndSignedBigintMoney() {
        assertThat(jdbc.queryForObject("""
                SELECT data_type FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'payment_transaction'
                  AND column_name = 'requested_at'
                """, String.class)).isEqualTo("timestamp");
        assertThat(jdbc.queryForObject("""
                SELECT column_type FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'payment_transaction'
                  AND column_name = 'requested_amount'
                """, String.class)).isEqualTo("bigint");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'app_user'
                  AND column_name IN ('email','password_hash')
                """, Integer.class)).isZero();
        long user = user();
        String sessionZone = jdbc.queryForObject("SELECT @@session.time_zone", String.class);
        try {
            jdbc.execute("SET time_zone = '+00:00'");
            LocalDateTime expected = LocalDateTime.of(2026, 10, 25, 15, 12, 30, 123456000);
            jdbc.update("UPDATE app_user SET identity_verified_at = ? WHERE user_id = ?", expected, user);
            assertThat(jdbc.queryForObject("SELECT identity_verified_at FROM app_user WHERE user_id = ?",
                    LocalDateTime.class, user)).isEqualTo(expected);
        } finally {
            // This value came from MySQL, not an HTTP request. Parameter binding avoids quoting it.
            jdbc.update("SET time_zone = ?", sessionZone);
        }
    }

    @Test
    void rejectsDuplicateIdentityButAllowsDifferentBirthDates() {
        byte[] hash = hash(UUID.randomUUID().toString());
        insert("app_user", fields("phone_hash", hash, "birth_date", LocalDate.of(2000, 1, 1)), true);
        assertRejected(() -> insert("app_user",
                fields("phone_hash", hash, "birth_date", LocalDate.of(2000, 1, 1)), true));
        insert("app_user", fields("phone_hash", hash, "birth_date", LocalDate.of(2001, 1, 1)), true);
    }

    @Test
    void auditRequiresADeviceOwnedByTheSameUser() {
        long owner = user();
        long stranger = user();
        long device = device(owner);
        insert("step_up_audit_log", fields("user_id", owner, "user_device_id", device,
                "auth_method", "PIN", "purpose", "LOGIN", "result", "SUCCESS"), false);
        assertRejected(() -> insert("step_up_audit_log", fields("user_id", stranger,
                "user_device_id", device, "auth_method", "PIN", "purpose", "LOGIN", "result", "SUCCESS"), false));
        assertRejected(() -> insert("step_up_audit_log", fields("user_id", owner,
                "auth_method", "PIN", "purpose", "LOGIN", "result", "SUCCESS"), false));
        assertRejected(() -> insert("step_up_audit_log", fields("user_id", owner,
                "user_device_id", device, "auth_method", "PASSWORD", "purpose", "LOGIN", "result", "SUCCESS"), false));
    }

    @Test
    void linkedAccountReconnectionKeepsIdentityAndCannotChangeOwnerThroughReferences() {
        long owner = user();
        long stranger = user();
        long account = account(owner);
        long product = product();
        assertRejected(() -> card(stranger, product, account));
        long card = card(owner, product, account);
        assertThat(card).isPositive();
        jdbc.update("UPDATE linked_account SET status = 'DISCONNECTED' WHERE linked_account_id = ?", account);
        jdbc.update("UPDATE linked_account SET status = 'CONNECTED' WHERE linked_account_id = ?", account);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM linked_account WHERE linked_account_id = ?",
                Integer.class, account)).isEqualTo(1);
        assertRejected(() -> insert("cashflow_item", fields("user_id", stranger,
                "linked_account_id", account, "title", "test", "direction", "EXPENSE", "amount", 1000L,
                "scheduled_date", MONTH, "recurrence_type", "NONE", "status", "ACTIVE"), true));
    }

    @Test
    void paymentRequestKeyIsGlobalAndRejectsZeroNegativeAndNonexistentReferences() {
        long owner = user();
        long merchant = merchant();
        String key = UUID.randomUUID().toString();
        payment(owner, merchant, key, 1000L);
        assertRejected(() -> payment(owner, merchant, key, 2000L));
        assertRejected(() -> payment(owner, merchant, UUID.randomUUID().toString(), 0L));
        assertRejected(() -> payment(owner, merchant, UUID.randomUUID().toString(), -1L));
        assertRejected(() -> payment(owner, Long.MAX_VALUE, UUID.randomUUID().toString(), 1000L));
    }

    @Test
    void benefitsAndLimitsCannotCrossCardProductsOrBeAppliedTwice() {
        Fixture a = fixture();
        long otherProduct = product();
        long benefit = benefit(a.product());
        long otherBenefit = benefit(otherProduct);
        long group = limit(a.product());
        long otherGroup = limit(otherProduct);
        insert("benefit_limit_link", fields("card_product_id", a.product(), "card_benefit_id", benefit,
                "limit_group_id", group), false);
        assertRejected(() -> insert("benefit_limit_link", fields("card_product_id", a.product(),
                "card_benefit_id", benefit, "limit_group_id", otherGroup), false));
        applied(a, benefit);
        assertRejected(() -> applied(a, benefit));
        assertRejected(() -> applied(a, otherBenefit));
        impact(a, group);
        assertRejected(() -> impact(a, group));
        assertRejected(() -> impact(a, otherGroup));
    }

    @Test
    void targetShapeAndDuplicateTargetsAreValidated() {
        long benefit = benefit(product());
        long merchant = merchant();
        insert("benefit_target", fields("card_benefit_id", benefit,
                "target_type", "BRAND", "merchant_id", merchant), false);
        assertRejected(() -> insert("benefit_target", fields("card_benefit_id", benefit,
                "target_type", "BRAND", "merchant_id", merchant), false));
        assertRejected(() -> insert("benefit_target", fields("card_benefit_id", benefit,
                "target_type", "MERCHANT"), false));
        assertRejected(() -> insert("benefit_target", fields("card_benefit_id", benefit,
                "target_type", "BRAND", "target_value", "STARBUCKS"), false));
        assertRejected(() -> insert("benefit_target", fields("card_benefit_id", benefit,
                "target_type", "BRAND", "merchant_id", Long.MAX_VALUE), false));
        insert("benefit_target", fields("card_benefit_id", benefit, "target_type", "CONDITION",
                "target_value", "FACILITY:DEPARTMENT_STORE", "is_excluded", true), false);
    }

    @Test
    void merchantIsOneBrandAndTransactionsPreserveTheirOwnPaymentConditions() {
        long merchant = merchant();
        String brandCode = jdbc.queryForObject("SELECT brand_code FROM merchant WHERE merchant_id = ?",
                String.class, merchant);
        assertRejected(() -> insert("merchant", fields("merchant_name", "another store",
                "brand_code", brandCode, "category_code", "CAFE"), true));
        long owner = user();
        long general = payment(owner, merchant, UUID.randomUUID().toString(), 1000L);
        long department = payment(owner, merchant, UUID.randomUUID().toString(), 1000L);
        jdbc.update("""
                UPDATE payment_transaction SET payment_channel = 'OFFLINE', facility_type = 'GENERAL',
                    merchant_context_snapshot = ? WHERE payment_transaction_id = ?
                """, "{\"source\":\"SERVER_POS_CONFIG\",\"brandCode\":\"TEST_BRAND\",\"categoryCode\":\"CAFE\"}", general);
        jdbc.update("""
                UPDATE payment_transaction SET payment_channel = 'OFFLINE', facility_type = 'DEPARTMENT_STORE'
                WHERE payment_transaction_id = ?
                """, department);
        jdbc.update("UPDATE merchant SET merchant_name = 'renamed brand', category_code = 'NEW' WHERE merchant_id = ?",
                merchant);
        assertThat(jdbc.queryForList("""
                SELECT facility_type FROM payment_transaction WHERE merchant_id = ?
                ORDER BY payment_transaction_id
                """, String.class, merchant)).containsExactly("GENERAL", "DEPARTMENT_STORE");
        assertThat(jdbc.queryForObject("""
                SELECT JSON_UNQUOTE(JSON_EXTRACT(merchant_context_snapshot, '$.categoryCode'))
                FROM payment_transaction WHERE payment_transaction_id = ?
                """, String.class, general)).isEqualTo("CAFE");
    }

    @Test
    void missingPaymentConditionsStayUnknownAndInvalidChannelIsRejected() {
        long payment = payment(user(), merchant(), UUID.randomUUID().toString(), 1000L);
        assertThat(jdbc.queryForObject("SELECT payment_channel FROM payment_transaction WHERE payment_transaction_id = ?",
                String.class, payment)).isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT facility_type FROM payment_transaction WHERE payment_transaction_id = ?",
                String.class, payment)).isNull();
        assertRejected(() -> jdbc.update("UPDATE payment_transaction SET payment_channel = 'INVALID' WHERE payment_transaction_id = ?",
                payment));
    }

    @Test
    void monthlySummariesRequireExplicitNonnegativeAmountsAndRejectDuplicates() {
        Fixture a = fixture();
        long id = insert("card_monthly_performance", fields("user_card_id", a.card(),
                "performance_month", MONTH, "recognized_amount", 0L), true);
        assertThat(jdbc.queryForObject("SELECT recognized_amount FROM card_monthly_performance WHERE card_monthly_performance_id = ?",
                Long.class, id)).isZero();
        assertRejected(() -> insert("card_monthly_performance", fields("user_card_id", a.card(),
                "performance_month", MONTH, "recognized_amount", 0L), true));
        assertRejected(() -> insert("card_monthly_performance", fields("user_card_id", a.card(),
                "performance_month", MONTH.plusMonths(1)), true));
        assertRejected(() -> insert("card_monthly_performance", fields("user_card_id", a.card(),
                "performance_month", MONTH.plusMonths(1), "recognized_amount", -1L), true));
        assertRejected(() -> insert("card_monthly_performance", fields("user_card_id", a.card(),
                "performance_month", MONTH.plusDays(1), "recognized_amount", 0L), true));
        long group = limit(a.product());
        Map<String, Object> usage = fields("user_card_id", a.card(), "card_product_id", a.product(),
                "limit_group_id", group, "usage_month", MONTH, "used_value", 0L);
        insert("monthly_limit_usage", usage, true);
        assertRejected(() -> insert("monthly_limit_usage", usage, true));
        assertRejected(() -> insert("monthly_limit_usage", fields("user_card_id", a.card(),
                "card_product_id", a.product(), "limit_group_id", group, "usage_month", MONTH.plusMonths(1)), true));
        assertRejected(() -> insert("monthly_limit_usage", fields("user_card_id", a.card(),
                "card_product_id", a.product(), "limit_group_id", group, "usage_month", MONTH.plusMonths(1),
                "used_value", -1L), true));
    }

    @Test
    void installmentAndRefundMustUseTheOriginalPaymentAndOwner() {
        Fixture a = fixture();
        Fixture b = fixture();
        long bill = bill(a);
        long item = item(a, bill, 1, 3333L, 50L);
        assertRejected(() -> item(a, bill, 1, 3333L, 50L));
        assertRejected(() -> item(b, bill, 1, 3333L, 50L));
        assertRejected(() -> insert("bill_item", fields("payment_transaction_id", a.payment(),
                "user_id", a.owner(), "user_card_id", a.card(), "installment_no", 2,
                "billing_month", MONTH, "principal_amount", 3333L, "fee_amount", 50L,
                "billed_amount", 3333L, "status", "PENDING"), true));
        refund(a, item, a.account());
        assertRejected(() -> refund(a, item, a.account()));
        long secondItem = item(a, bill, 2, 3333L, 50L);
        assertRejected(() -> refund(a, secondItem, b.account()));
        assertRejected(() -> refund(b, secondItem, b.account()));
        assertRejected(() -> insert("refund", fields("payment_transaction_id", a.payment(),
                "user_id", a.owner(), "request_key", UUID.randomUUID().toString(),
                "principal_amount", 1000L, "fee_amount", 0L, "refund_amount", 1000L,
                "status", "PENDING"), true));
    }

    @Test
    void notificationDeduplicationIsScopedToUser() {
        long a = user();
        long b = user();
        String key = UUID.randomUUID().toString();
        notification(a, key);
        assertRejected(() -> notification(a, key));
        notification(b, key);
    }

    @Test
    void referencedFinancialHistoryCannotBeCascadeDeleted() {
        Fixture fixture = fixture();
        assertRejected(() -> jdbc.update("DELETE FROM app_user WHERE user_id = ?", fixture.owner()));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_transaction WHERE payment_transaction_id = ?",
                Integer.class, fixture.payment())).isEqualTo(1);
    }

    private long user() {
        return insert("app_user", fields("name", "schema-test", "birth_date", LocalDate.of(2000, 1, 1),
                "phone_hash", hash(UUID.randomUUID().toString())), true);
    }

    private long device(long owner) {
        return insert("user_device", fields("user_id", owner, "device_uuid", UUID.randomUUID().toString(),
                "platform", "ANDROID"), true);
    }

    private long account(long owner) {
        return insert("linked_account", fields("user_id", owner, "bank_code", "TEST",
                "account_no_enc", new byte[]{1, 2}, "account_no_hash", hash(UUID.randomUUID().toString()),
                "connected_at", now()), true);
    }

    private long product() {
        return insert("card_product", fields("issuer_code", "TEST", "product_name", UUID.randomUUID().toString(),
                "card_type", "CREDIT", "billing_cutoff_days", 14), true);
    }

    private long card(long owner, long product, long account) {
        return insert("user_card", fields("user_id", owner, "card_product_id", product,
                "linked_account_id", account, "card_no_enc", new byte[]{1, 2},
                "card_no_hash", hash(UUID.randomUUID().toString()), "card_last4", "1234",
                "status", "ACTIVE", "registered_at", now()), true);
    }

    private long merchant() {
        return insert("merchant", fields("merchant_name", "schema-test", "category_code", "CAFE",
                "brand_code", UUID.randomUUID().toString()), true);
    }

    private long payment(long owner, long merchant, String key, long amount) {
        return insert("payment_transaction", fields("user_id", owner, "merchant_id", merchant,
                "request_key", key, "request_fingerprint", hash(key), "source", "YAMU",
                "status", "REQUESTED", "requested_amount", amount, "requested_at", now()), true);
    }

    private Fixture fixture() {
        long owner = user();
        long account = account(owner);
        long product = product();
        long card = card(owner, product, account);
        long payment = payment(owner, merchant(), UUID.randomUUID().toString(), 10000L);
        jdbc.update("""
                UPDATE payment_transaction SET user_card_id = ?, card_product_id = ?,
                  approved_amount = 10000, recognized_performance_amount = 10000,
                  performance_month = ?, installment_months = 3, approved_at = ?, status = 'APPROVED'
                WHERE payment_transaction_id = ?
                """, card, product, MONTH, now(), payment);
        return new Fixture(owner, account, product, card, payment);
    }

    @Test
    void unknownPolicyStartDatesAreAllowedButKnownDateRangesMustBeOrdered() {
        long product = product();
        Map<String, Long> policies = Map.of("card_benefit", benefit(product), "limit_group", limit(product));
        policies.forEach((table, id) -> {
            String idColumn = table + "_id";
            jdbc.update("UPDATE " + table + " SET valid_from = NULL, valid_until = ? WHERE " + idColumn + " = ?",
                    MONTH.plusMonths(1), id);
            assertThat(jdbc.queryForObject("SELECT valid_from FROM " + table + " WHERE " + idColumn + " = ?",
                    LocalDate.class, id)).isNull();
            assertThat(jdbc.queryForObject("SELECT valid_until FROM " + table + " WHERE " + idColumn + " = ?",
                    LocalDate.class, id)).isEqualTo(MONTH.plusMonths(1));
            assertRejected(() -> jdbc.update("UPDATE " + table + " SET valid_from = ?, valid_until = ? WHERE " + idColumn + " = ?",
                    MONTH, MONTH, id));
            assertRejected(() -> jdbc.update("UPDATE " + table + " SET valid_from = ?, valid_until = ? WHERE " + idColumn + " = ?",
                    MONTH.plusMonths(1), MONTH, id));
            jdbc.update("UPDATE " + table + " SET valid_from = ?, valid_until = NULL WHERE " + idColumn + " = ?",
                    MONTH, id);
        });
    }

    @Test
    void limitConsumptionCannotExceedAvailableBalanceAndOriginalBasisSurvivesRestoration() {
        Fixture a = fixture();
        long group = limit(a.product());
        jdbc.update("UPDATE limit_group SET limit_unit = 'AMOUNT', base_limit_value = 10000 WHERE limit_group_id = ?", group);
        jdbc.update("UPDATE payment_transaction SET performance_basis_amount = 350000 WHERE payment_transaction_id = ?", a.payment());
        assertRejected(() -> jdbc.update("UPDATE payment_transaction SET performance_basis_amount = -1 WHERE payment_transaction_id = ?", a.payment()));
        long impact = insert("payment_limit_usage", fields("payment_transaction_id", a.payment(),
                "card_product_id", a.product(), "limit_group_id", group, "remaining_before", 300L,
                "consumed_value", 300L, "usage_date", MONTH, "usage_month", MONTH), true);
        assertRejected(() -> jdbc.update("UPDATE payment_limit_usage SET remaining_before = NULL WHERE payment_limit_usage_id = ?", impact));
        assertRejected(() -> jdbc.update("UPDATE payment_limit_usage SET remaining_before = -1 WHERE payment_limit_usage_id = ?", impact));
        assertRejected(() -> jdbc.update("UPDATE payment_limit_usage SET consumed_value = 301 WHERE payment_limit_usage_id = ?", impact));
        jdbc.update("UPDATE payment_limit_usage SET restored_at = ? WHERE payment_limit_usage_id = ?", now(), impact);
        assertThat(jdbc.queryForObject("SELECT remaining_before FROM payment_limit_usage WHERE payment_limit_usage_id = ?", Long.class, impact)).isEqualTo(300L);
        assertThat(jdbc.queryForObject("SELECT consumed_value FROM payment_limit_usage WHERE payment_limit_usage_id = ?", Long.class, impact)).isEqualTo(300L);
        assertThat(jdbc.queryForObject("SELECT performance_basis_amount FROM payment_transaction WHERE payment_transaction_id = ?", Long.class, a.payment())).isEqualTo(350000L);
        jdbc.update("UPDATE payment_transaction SET performance_basis_amount = 0 WHERE payment_transaction_id = ?", a.payment());
        assertThat(jdbc.queryForObject("SELECT performance_basis_amount FROM payment_transaction WHERE payment_transaction_id = ?", Long.class, a.payment())).isZero();
    }

    private long benefit(long product) {
        return insert("card_benefit", fields("card_product_id", product, "benefit_code", UUID.randomUUID().toString(),
                "policy_version", 1, "benefit_name", "test", "benefit_type", "DISCOUNT",
                "calculation_type", "RATE", "rate", new java.math.BigDecimal("0.100000"),
                "value_unit", "KRW", "valid_from", MONTH), false);
    }

    private long limit(long product) {
        return insert("limit_group", fields("card_product_id", product, "group_code", UUID.randomUUID().toString(),
                "policy_version", 1, "group_name", "test", "period_type", "DAILY", "limit_unit", "COUNT",
                "base_limit_value", 1L, "valid_from", MONTH), false);
    }

    private void applied(Fixture a, long benefit) {
        insert("payment_benefit", fields("payment_transaction_id", a.payment(), "card_product_id", a.product(),
                "card_benefit_id", benefit, "benefit_value", 1000L, "value_unit", "KRW",
                "krw_amount", 1000L), false);
    }

    private void impact(Fixture a, long group) {
        insert("payment_limit_usage", fields("payment_transaction_id", a.payment(), "card_product_id", a.product(),
                "limit_group_id", group, "remaining_before", 1L, "consumed_value", 1L,
                "usage_date", MONTH, "usage_month", MONTH), true);
    }

    private long bill(Fixture a) {
        return insert("card_bill", fields("user_id", a.owner(), "user_card_id", a.card(),
                "linked_account_id", a.account(), "billing_month", MONTH, "period_start", MONTH,
                "period_end", MONTH.plusDays(10), "withdrawal_date", MONTH.plusDays(24), "status", "PENDING"), true);
    }

    private long item(Fixture a, long bill, int installment, long principal, long fee) {
        return insert("bill_item", fields("payment_transaction_id", a.payment(), "user_id", a.owner(),
                "user_card_id", a.card(), "card_bill_id", bill, "installment_no", installment,
                "billing_month", MONTH, "principal_amount", principal, "fee_amount", fee,
                "billed_amount", principal + fee, "status", "PENDING"), true);
    }

    private void refund(Fixture a, long item, long account) {
        insert("refund", fields("payment_transaction_id", a.payment(), "bill_item_id", item,
                "user_id", a.owner(), "linked_account_id", account, "request_key", UUID.randomUUID().toString(),
                "principal_amount", 3333L, "fee_amount", 50L, "refund_amount", 3383L, "status", "PENDING"), true);
    }

    private void notification(long owner, String key) {
        insert("notification", fields("user_id", owner, "notification_type", "TEST",
                "deduplication_key", key, "title", "test", "body", "test"), true);
    }

    private long insert(String table, Map<String, Object> values, boolean mutable) {
        Map<String, Object> row = new LinkedHashMap<>(values);
        row.put("created_at", now());
        if (mutable) row.put("updated_at", now());
        String sql = "INSERT INTO " + table + " (" + String.join(", ", row.keySet()) + ") VALUES ("
                + row.keySet().stream().map(key -> "?").collect(Collectors.joining(", ")) + ")";
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            int index = 1;
            for (Object value : row.values()) statement.setObject(index++, value);
            return statement;
        }, keys);
        return keys.getKey().longValue();
    }

    private Map<String, Object> fields(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }

    private static byte[] hash(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private static void assertRejected(Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOf(DataAccessException.class)
                .satisfies(failure -> {
                    Throwable cause = ((DataAccessException) failure).getMostSpecificCause();
                    assertThat(cause).isInstanceOf(SQLException.class);
                    // MySQL CHECK/missing-required-column failures use HY000 and may be uncategorized
                    // by Spring. Require a constraint error; connection/syntax errors must still fail.
                    assertThat(((SQLException) cause).getErrorCode())
                            .isIn(1048, 1062, 1364, 1451, 1452, 3819);
                });
    }

    private record Fixture(long owner, long account, long product, long card, long payment) {}
}
