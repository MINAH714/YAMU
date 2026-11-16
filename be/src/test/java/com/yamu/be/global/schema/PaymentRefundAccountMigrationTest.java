package com.yamu.be.global.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yamu.be.support.MySqlTestSupport;
import com.yamu.be.support.OwnershipFixtures;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

/**
 * 체크카드 환불 계좌 칸(V20261116_2, S15P31E103-323)을 추가할 때 이미 취소된 체크카드 결제는 카드의 그때 연결 계좌로 채운다.
 * 환급 내역에 보이지 않는 결제(0원 승인·SEED·신용카드·취소 전)와 카드에 연결 계좌가 없는 결제는 비워 둔다.
 * 칸에는 같은 사용자의 계좌만 들어간다(외래 키).
 */
@TestPropertySource(properties = {
        "spring.application.name=yamu-payment-refund-account-migration-test",
        "spring.flyway.target=20261116.1",
        "spring.jpa.hibernate.ddl-auto=none"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PaymentRefundAccountMigrationTest extends MySqlTestSupport {

    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;

    @Test
    void 취소된_체크카드_결제는_카드의_지금_연결_계좌로_채우고_나머지는_비워_둔다() {
        OwnershipFixtures fixtures = new OwnershipFixtures(jdbc);
        long owner = fixtures.user();
        long account = fixtures.account(owner);
        long check = fixtures.product();
        jdbc.update("UPDATE card_product SET card_type = 'CHECK' WHERE card_product_id = ?", check);
        long credit = fixtures.product();
        long checkCard = fixtures.card(owner, check, account, "ACTIVE");
        long creditCard = fixtures.card(owner, credit, account, "ACTIVE");
        long noAccountCard = fixtures.card(owner, check, null, "INACTIVE");
        long merchant = merchant();

        long canceled = payment(owner, checkCard, check, merchant, "CANCELED", "YAMU", 15_000);
        long zero = payment(owner, checkCard, check, merchant, "CANCELED", "YAMU", 0);
        long seed = payment(owner, checkCard, check, merchant, "CANCELED", "SEED", 15_000);
        long approved = payment(owner, checkCard, check, merchant, "APPROVED", "YAMU", 15_000);
        long creditCanceled = payment(owner, creditCard, credit, merchant, "CANCELED", "YAMU", 15_000);
        long noAccount = payment(owner, noAccountCard, check, merchant, "CANCELED", "YAMU", 15_000);

        Flyway upgrade = Flyway.configure().dataSource(flyway.getConfiguration().getDataSource())
                .locations(flyway.getConfiguration().getLocations())
                .target(MigrationVersion.fromVersion("20261116.2")).load();
        assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);

        assertThat(refundAccount(canceled)).isEqualTo(account);
        assertThat(refundAccount(zero)).isNull();
        assertThat(refundAccount(seed)).isNull();
        assertThat(refundAccount(approved)).isNull();
        assertThat(refundAccount(creditCanceled)).isNull();
        assertThat(refundAccount(noAccount)).isNull();

        // 다른 사용자의 계좌는 들어가지 않는다
        long stranger = fixtures.user();
        long strangerAccount = fixtures.account(stranger);
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE payment_transaction SET refund_linked_account_id = ? WHERE payment_transaction_id = ?",
                strangerAccount, approved)).isInstanceOf(DataIntegrityViolationException.class);
    }

    private Long refundAccount(long paymentId) {
        return jdbc.queryForObject(
                "SELECT refund_linked_account_id FROM payment_transaction WHERE payment_transaction_id = ?", Long.class,
                paymentId);
    }

    private long payment(long user, long userCard, long product, long merchant, String status, String source,
            long approvedAmount) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Map<String, Object> row = new HashMap<>();
        row.put("user_id", user);
        row.put("user_card_id", userCard);
        row.put("card_product_id", product);
        row.put("merchant_id", merchant);
        row.put("request_key", UUID.randomUUID().toString());
        row.put("request_fingerprint", new byte[32]);
        row.put("source", source);
        row.put("status", status);
        row.put("requested_amount", 15_000L);
        row.put("approved_amount", approvedAmount);
        row.put("installment_months", 1);
        row.put("requested_at", now.minusDays(2));
        row.put("approved_at", now.minusDays(2));
        // 승인된 결제는 실적 월이 있어야 한다(ck_payment_approval)
        row.put("performance_month", now.toLocalDate().withDayOfMonth(1));
        if ("CANCELED".equals(status)) {
            row.put("canceled_at", now.minusDays(1));
        }
        row.put("created_at", now);
        row.put("updated_at", now);
        // 넣는 칸만 적는다. 다른 칸은 기본값(payment_channel 'UNKNOWN' 등)을 쓴다
        return new SimpleJdbcInsert(jdbc).withTableName("payment_transaction")
                .usingColumns(row.keySet().toArray(String[]::new))
                .usingGeneratedKeyColumns("payment_transaction_id").executeAndReturnKey(row).longValue();
    }

    private long merchant() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return new SimpleJdbcInsert(jdbc).withTableName("merchant").usingGeneratedKeyColumns("merchant_id")
                .executeAndReturnKey(Map.of("merchant_name", "테스트 카페", "brand_code", "MIG-" + UUID.randomUUID(),
                        "merchant_category_code", "F01-CAFE", "created_at", now, "updated_at", now))
                .longValue();
    }
}
