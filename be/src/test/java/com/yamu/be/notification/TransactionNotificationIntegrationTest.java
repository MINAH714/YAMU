package com.yamu.be.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.yamu.be.payment.billing.confirm.BillConfirmedEvent;
import com.yamu.be.payment.billing.refund.RefundCompletedEvent;
import com.yamu.be.payment.billing.withdraw.BillUnpaidEvent;
import com.yamu.be.payment.billing.withdraw.BillWithdrawnEvent;
import com.yamu.be.payment.domain.PaymentApprovedEvent;
import com.yamu.be.payment.domain.PaymentStatus;
import com.yamu.be.payment.domain.PaymentTransaction;
import com.yamu.be.payment.domain.PaymentTransactionRepository;
import com.yamu.be.payment.bank.TransferResult;
import com.yamu.be.support.MySqlTestSupport;
import com.yamu.be.support.OwnershipFixtures;
import java.nio.ByteBuffer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 거래 알림(Task 9.3.2)을 실제 MySQL로 확인한다. 결제 승인·취소는 결제 엔티티를 실제로 바꿔 저장하고(도메인 사건 발행),
 * 청구·환급 사건은 그 모듈이 하듯 트랜잭션 안에서 발행한다. 커밋 뒤 리스너가 새 트랜잭션에서 쌓는지 보려고 테스트에 트랜잭션을 걸지 않는다.
 */
@TestPropertySource(properties = "spring.application.name=yamu-transaction-notification-test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TransactionNotificationIntegrationTest extends MySqlTestSupport {

    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transaction;
    @Autowired PaymentTransactionRepository payments;
    @Autowired ApplicationEventPublisher events;

    long user;
    long card;
    long merchant;

    @BeforeEach
    void setUp() {
        OwnershipFixtures fixtures = new OwnershipFixtures(jdbc);
        user = fixtures.user();
        long account = fixtures.account(user);
        card = fixtures.card(user, fixtures.product(), account, "ACTIVE");
        jdbc.update("UPDATE user_card SET card_alias = '생활비 카드' WHERE user_card_id = ?", card);
        merchant = merchant();
    }

    // ───────── 결제 승인·취소 ─────────

    @Test
    void 승인을_커밋하면_결제_승인_알림이_하나_쌓인다() {
        long paymentId = payment("PROCESSING");

        approve(paymentId);

        List<Map<String, Object>> rows = notifications();
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row.get("notification_type")).isEqualTo("PAYMENT_RESULT");
        assertThat(row.get("deduplication_key")).isEqualTo("PAYMENT_RESULT:" + paymentId + ":APPROVED");
        assertThat(row.get("title")).isEqualTo("결제 승인");
        assertThat(row.get("body")).isEqualTo("테스트 카페 15,000원을 생활비 카드(으)로 결제했어요 (즉시할인 1,000원)");
        assertThat(row.get("target_type")).isEqualTo("PAYMENT_TRANSACTION");
        assertThat(((Number) row.get("target_id")).longValue()).isEqualTo(paymentId);
        assertThat(row.get("read_at")).isNull();
    }

    @Test
    void 승인이_롤백되면_알림을_쌓지_않는다() {
        long paymentId = payment("PROCESSING");

        transaction.executeWithoutResult(status -> {
            PaymentTransaction payment = payments.findById(paymentId).orElseThrow();
            payment.approve(14_000L, 14_000L, LocalDate.of(2026, 11, 1), now(), null);
            payments.saveAndFlush(payment);
            status.setRollbackOnly();
        });

        assertThat(notifications()).isEmpty();
        assertThat(status(paymentId)).isEqualTo("PROCESSING");
    }

    @Test
    void 승인한_결제를_취소하면_취소_알림이_따로_쌓인다() {
        long paymentId = payment("PROCESSING");
        approve(paymentId);

        transaction.executeWithoutResult(status -> {
            PaymentTransaction payment = payments.findById(paymentId).orElseThrow();
            payment.cancel(now());
            payments.saveAndFlush(payment);
        });

        assertThat(notifications()).extracting(row -> row.get("deduplication_key")).containsExactlyInAnyOrder(
                "PAYMENT_RESULT:" + paymentId + ":APPROVED", "PAYMENT_RESULT:" + paymentId + ":CANCELED");
        assertThat(notification("PAYMENT_RESULT:" + paymentId + ":CANCELED").get("body"))
                .isEqualTo("테스트 카페 15,000원 결제가 취소됐어요");
    }

    @Test
    void 같은_사건을_두_번_받아도_알림은_하나다() {
        long paymentId = payment("APPROVED");

        transaction.executeWithoutResult(status -> events.publishEvent(new PaymentApprovedEvent(paymentId, user)));
        transaction.executeWithoutResult(status -> events.publishEvent(new PaymentApprovedEvent(paymentId, user)));

        assertThat(notifications()).hasSize(1);
    }

    @Test
    void 거래_알림을_끄면_쌓지_않는다() {
        jdbc.update("UPDATE app_user SET notification_settings = JSON_OBJECT('transactionEnabled', false) "
                + "WHERE user_id = ?", user);
        long paymentId = payment("PROCESSING");

        approve(paymentId);

        assertThat(notifications()).isEmpty();
        assertThat(status(paymentId)).isEqualTo("APPROVED");
    }

    @Test
    void 알림을_못_쌓아도_승인은_커밋되고_예외가_나지_않는다() {
        // 설정 저장값이 깨져 알림 쓰기가 IllegalStateException으로 실패한다
        jdbc.update("UPDATE app_user SET notification_settings = JSON_OBJECT('transactionEnabled', 'yes') "
                + "WHERE user_id = ?", user);
        long paymentId = payment("PROCESSING");

        assertThatCode(() -> approve(paymentId)).doesNotThrowAnyException();

        assertThat(status(paymentId)).isEqualTo("APPROVED");
        assertThat(notifications()).isEmpty();
    }

    @Test
    void 남의_결제_번호로_온_사건은_문구를_만들지_않는다() {
        long other = new OwnershipFixtures(jdbc).user();
        long paymentId = payment("APPROVED");

        transaction.executeWithoutResult(status -> events.publishEvent(new PaymentApprovedEvent(paymentId, other)));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE user_id IN (?, ?)", Long.class,
                user, other)).isZero();
    }

    // ───────── 청구·출금·환급 ─────────

    @Test
    void 청구_확정_출금_알림은_청구서로_이동한다() {
        long billId = bill(LocalDate.of(2026, 12, 1));

        publish(new BillConfirmedEvent(billId, user, card, 52_300L, LocalDate.of(2026, 12, 15), false));
        publish(new BillWithdrawnEvent(billId, user, card, 52_300L, LocalDate.of(2026, 12, 15), "FIN-1"));

        Map<String, Object> confirmed = notification("BILL_CONFIRMED:" + billId);
        assertThat(confirmed.get("title")).isEqualTo("생활비 카드 12월 청구 확정");
        assertThat(confirmed.get("body")).isEqualTo("52,300원이 12월 15일 결제 계좌에서 출금될 예정이에요");
        assertThat(confirmed.get("target_type")).isEqualTo("CARD_BILL");
        assertThat(((Number) confirmed.get("target_id")).longValue()).isEqualTo(billId);
        assertThat(notification("BILL_WITHDRAWN:" + billId).get("body"))
                .isEqualTo("생활비 카드 12월 청구액 52,300원이 12월 15일에 출금됐어요");
    }

    @Test
    void 미납_사건이_와도_알림은_쌓지_않는다() {
        long billId = bill(LocalDate.of(2026, 12, 1));

        // Task 9.3.4(2026-11-13): 미납은 홈 '할 일'과 승인 거절로 보이고 앱 알림은 만들지 않는다
        publish(new BillUnpaidEvent(billId, user, card, 52_300L, TransferResult.RejectReason.INSUFFICIENT_BALANCE));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE user_id = ?", Long.class, user))
                .isZero();
    }

    @Test
    void 영원_청구는_출금_없이_마감됐다고_알린다() {
        long billId = bill(LocalDate.of(2026, 12, 1));

        publish(new BillConfirmedEvent(billId, user, card, 0L, LocalDate.of(2026, 12, 15), true));

        assertThat(notification("BILL_CONFIRMED:" + billId).get("body")).isEqualTo("이번 청구액이 0원이라 출금 없이 마감됐어요");
    }

    @Test
    void 환급_입금_알림은_원래_결제로_이동한다() {
        long paymentId = payment("CANCELED");

        publish(new RefundCompletedEvent(77L, user, paymentId, 15_000L));

        Map<String, Object> row = notification("REFUND_COMPLETED:77");
        assertThat(row.get("title")).isEqualTo("환급 입금 완료");
        assertThat(row.get("body")).isEqualTo("테스트 카페 취소의 환급금 15,000원이 결제 계좌로 들어왔어요");
        assertThat(row.get("target_type")).isEqualTo("PAYMENT_TRANSACTION");
        assertThat(((Number) row.get("target_id")).longValue()).isEqualTo(paymentId);
    }

    // ───────── 도움 ─────────

    private void approve(long paymentId) {
        transaction.executeWithoutResult(status -> {
            PaymentTransaction payment = payments.findById(paymentId).orElseThrow();
            payment.approve(14_000L, 14_000L, LocalDate.of(2026, 11, 1), now(), null);
            payments.saveAndFlush(payment);
        });
        assertThat(status(paymentId)).isEqualTo(PaymentStatus.APPROVED.name());
    }

    private void publish(Object event) {
        transaction.executeWithoutResult(status -> events.publishEvent(event));
    }

    private List<Map<String, Object>> notifications() {
        return jdbc.queryForList("SELECT * FROM notification WHERE user_id = ? ORDER BY notification_id", user);
    }

    private Map<String, Object> notification(String key) {
        return jdbc.queryForMap("SELECT * FROM notification WHERE user_id = ? AND deduplication_key = ?", user, key);
    }

    private String status(long paymentId) {
        return jdbc.queryForObject("SELECT status FROM payment_transaction WHERE payment_transaction_id = ?",
                String.class, paymentId);
    }

    private long payment(String status) {
        LocalDateTime now = now();
        UUID a = UUID.randomUUID();
        Map<String, Object> row = new HashMap<>();
        row.put("user_id", user);
        row.put("merchant_id", merchant);
        row.put("user_card_id", card);
        row.put("card_product_id", jdbc.queryForObject(
                "SELECT card_product_id FROM user_card WHERE user_card_id = ?", Long.class, card));
        row.put("request_key", UUID.randomUUID().toString());
        row.put("request_fingerprint", ByteBuffer.allocate(32).putLong(a.getMostSignificantBits())
                .putLong(a.getLeastSignificantBits()).array());
        row.put("source", "YAMU");
        row.put("status", status);
        row.put("requested_amount", 15_000L);
        row.put("installment_months", 1);
        row.put("requested_at", now);
        if (!status.equals("PROCESSING")) {
            row.put("approved_amount", 14_000L);
            row.put("recognized_performance_amount", 14_000L);
            row.put("performance_month", LocalDate.of(2026, 11, 1));
            row.put("approved_at", now);
        }
        if (status.equals("CANCELED")) {
            row.put("canceled_at", now);
        }
        row.put("created_at", now);
        row.put("updated_at", now);
        return insert("payment_transaction", "payment_transaction_id", row);
    }

    private long bill(LocalDate billingMonth) {
        LocalDateTime now = now();
        Map<String, Object> row = new HashMap<>();
        row.put("user_id", user);
        row.put("user_card_id", card);
        row.put("billing_month", billingMonth);
        row.put("period_start", billingMonth.minusMonths(1).withDayOfMonth(2));
        row.put("period_end", billingMonth.withDayOfMonth(1));
        row.put("withdrawal_date", billingMonth.withDayOfMonth(15));
        row.put("status", "ACCUMULATING");
        row.put("created_at", now);
        row.put("updated_at", now);
        return insert("card_bill", "card_bill_id", row);
    }

    private long merchant() {
        LocalDateTime now = now();
        return insert("merchant", "merchant_id", Map.of("merchant_name", "테스트 카페",
                "brand_code", "TEST_" + UUID.randomUUID().toString().substring(0, 8),
                "merchant_category_code", "COFFEE_SHOP", "created_at", now, "updated_at", now));
    }

    private long insert(String table, String idColumn, Map<String, Object> row) {
        return new SimpleJdbcInsert(jdbc).withTableName(table).usingGeneratedKeyColumns(idColumn)
                .usingColumns(row.keySet().toArray(String[]::new))
                .executeAndReturnKey(row).longValue();
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}
