package com.yamu.be.payment.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.payment.bank.SsafyTransferApi;
import com.yamu.be.payment.billing.confirm.BillConfirmer;
import com.yamu.be.payment.billing.refund.RefundDepositor;
import com.yamu.be.payment.billing.withdraw.BillWithdrawer;
import com.yamu.be.payment.cancel.BillItemCreditBillingCanceler;
import com.yamu.be.payment.cancel.CreditBillingCanceler;
import com.yamu.be.payment.domain.PaymentTransactionRepository;
import com.yamu.be.support.MutableClock;
import com.yamu.be.support.MySqlTestSupport;
import com.yamu.be.support.OwnershipFixtures;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;

/**
 * 회원 탈퇴 때 남은 청구서·환급 닫기(S15P31E103-443)를 실제 MySQL로 확인한다. 청구서·회차·환급은 SQL로 넣고, 금융망은 부르면 안 되므로
 * 가짜로 두고 불리지 않았는지만 본다. 탈퇴 API 전체 흐름은 {@code WithdrawalIntegrationTest}가 본다.
 */
@TestPropertySource(properties = "spring.application.name=yamu-withdrawn-member-billing-test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WithdrawnMemberBillingCloserIntegrationTest extends MySqlTestSupport {

    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    static final DateTimeFormatter REQUEST_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    static final YearMonth OCTOBER = YearMonth.of(2026, 10);
    static final YearMonth NOVEMBER = YearMonth.of(2026, 11);
    static final YearMonth DECEMBER = YearMonth.of(2026, 12);

    @Container
    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }
    }

    @MockitoBean SsafyTransferApi transfer;

    @Autowired WithdrawnMemberBillingCloser closer;
    @Autowired BillConfirmer confirmer;
    @Autowired BillWithdrawer withdrawer;
    @Autowired RefundDepositor depositor;
    @Autowired BillItemCreditBillingCanceler canceler;
    @Autowired PaymentTransactionRepository payments;
    @Autowired TransactionTemplate transaction;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired MutableClock clock;

    OwnershipFixtures owners;
    BillFixtures bills;
    long user;
    long account;
    long card;
    long merchant;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM refund");
        jdbc.update("DELETE FROM bill_item");
        jdbc.update("DELETE FROM card_bill");
        jdbc.update("DELETE FROM payment_transaction");
        jdbc.update("DELETE FROM user_card");
        jdbc.update("DELETE FROM linked_account");
        jdbc.update("DELETE FROM card_product");
        jdbc.update("DELETE FROM merchant");
        jdbc.update("DELETE FROM app_user");
        redis.getRequiredConnectionFactory().getConnection().serverCommands().flushAll();
        clock.reset();
        owners = new OwnershipFixtures(jdbc);
        bills = new BillFixtures(jdbc);
        user = owners.user();
        account = owners.account(user);
        card = owners.card(user, owners.product(), account, "ACTIVE");
        merchant = bills.merchant("탈퇴 테스트 가맹점");
    }

    // ───────── 닫기 ─────────

    @Test
    void 진행_중_확정_미납_청구서와_대기_입금_실패_환급을_닫고_끝난_것과_다른_사용자_것은_그대로_둔다() {
        long accumulating = bills.accumulating(user, card, DECEMBER, LocalDate.of(2026, 12, 15));
        long confirmed = bills.confirmed(user, card, NOVEMBER, LocalDate.of(2026, 11, 16), account, 30_000);
        long unpaid = bills.unpaid(user, card, OCTOBER, LocalDate.of(2026, 10, 15), account, 20_000, 0);
        long paid = bills.paid(user, card, YearMonth.of(2026, 9), LocalDate.of(2026, 9, 15), account, 10_000, "W1");
        long waived = bills.unpaid(user, card, YearMonth.of(2026, 8), LocalDate.of(2026, 8, 17), account, 5_000, 5_000);
        long pendingRefund = refund(paid, 4_000, "PENDING");
        long failedRefund = refund(paid, 3_000, "FAILED");
        long completedRefund = refund(paid, 2_000, "COMPLETED");
        long other = owners.user();
        long otherAccount = owners.account(other);
        long otherCard = owners.card(other, owners.product(), otherAccount, "ACTIVE");
        long otherBill = bills.accumulating(other, otherCard, DECEMBER, LocalDate.of(2026, 12, 15));

        WithdrawnMemberBillingCloser.Closed closed = transaction.execute(status -> closer.closeAll(user));

        assertThat(closed.billIds()).containsExactly(accumulating, confirmed, unpaid);
        assertThat(closed.refundIds()).containsExactly(pendingRefund, failedRefund);
        assertThat(billStatus(accumulating)).isEqualTo("CLOSED_BY_WITHDRAWAL");
        assertThat(billStatus(confirmed)).isEqualTo("CLOSED_BY_WITHDRAWAL");
        assertThat(billStatus(unpaid)).isEqualTo("CLOSED_BY_WITHDRAWAL");
        assertThat(billStatus(paid)).isEqualTo("PAID");
        assertThat(billStatus(waived)).isEqualTo("CANCELED");
        assertThat(billStatus(otherBill)).isEqualTo("ACCUMULATING");
        assertThat(refundStatus(pendingRefund)).isEqualTo("CLOSED_BY_WITHDRAWAL");
        assertThat(refundStatus(failedRefund)).isEqualTo("CLOSED_BY_WITHDRAWAL");
        assertThat(refundStatus(completedRefund)).isEqualTo("COMPLETED");
        // 돈은 움직이지 않고 금액·이력은 그대로다
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT confirmed_amount, paid_amount, failed_at FROM card_bill WHERE card_bill_id = ?", unpaid);
        assertThat(((Number) row.get("confirmed_amount")).longValue()).isEqualTo(20_000);
        assertThat(((Number) row.get("paid_amount")).longValue()).isZero();
        assertThat(row.get("failed_at")).isNotNull();
        verifyNoBankCalls();
    }

    @Test
    void 남은_것이_없으면_아무것도_닫지_않는다() {
        long paid = bills.paid(user, card, NOVEMBER, LocalDate.of(2026, 11, 16), account, 10_000, "W1");

        WithdrawnMemberBillingCloser.Closed closed = transaction.execute(status -> closer.closeAll(user));

        assertThat(closed.billIds()).isEmpty();
        assertThat(closed.refundIds()).isEmpty();
        assertThat(billStatus(paid)).isEqualTo("PAID");
    }

    @Test
    void 탈퇴_트랜잭션_밖에서_부르면_닫지_않는다() {
        long accumulating = bills.accumulating(user, card, DECEMBER, LocalDate.of(2026, 12, 15));

        assertThatThrownBy(() -> closer.closeAll(user)).isInstanceOf(IllegalTransactionStateException.class);
        assertThat(billStatus(accumulating)).isEqualTo("ACCUMULATING");
    }

    // ───────── 처리 중인 출금·입금 ─────────

    @Test
    void 출금을_요청하고_유예_안이면_409이고_아무것도_닫지_않는다_유예가_지나면_닫는다() {
        long accumulating = bills.accumulating(user, card, DECEMBER, LocalDate.of(2026, 12, 15));
        long confirmed = bills.confirmed(user, card, NOVEMBER, LocalDate.of(2026, 11, 16), account, 30_000);
        jdbc.update("UPDATE card_bill SET fin_request_no = ? WHERE card_bill_id = ?", requestNo(), confirmed);

        assertThatThrownBy(() -> closer.requireNothingInFlight(user)).isInstanceOfSatisfying(BusinessException.class,
                e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.BILL_OUTSTANDING);
                    assertThat(e.getDetails()).isEqualTo(Map.of("billIds", List.of(confirmed)));
                });
        assertThatThrownBy(() -> transaction.execute(status -> closer.closeAll(user)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.BILL_OUTSTANDING));
        assertThat(billStatus(accumulating)).isEqualTo("ACCUMULATING");
        assertThat(billStatus(confirmed)).isEqualTo("CONFIRMED");

        // 유예(30초)가 지나도 결과가 안 정해진 요청(운영 확인 대기 등)은 영원히 막지 않고 닫는다
        clock.advance(Duration.ofSeconds(31));
        closer.requireNothingInFlight(user);
        transaction.execute(status -> closer.closeAll(user));
        assertThat(billStatus(accumulating)).isEqualTo("CLOSED_BY_WITHDRAWAL");
        assertThat(billStatus(confirmed)).isEqualTo("CLOSED_BY_WITHDRAWAL");
    }

    @Test
    void 미납_청구서에_남은_요청_번호는_처리_중으로_보지_않는다() {
        long unpaid = bills.unpaid(user, card, OCTOBER, LocalDate.of(2026, 10, 15), account, 20_000, 0);
        jdbc.update("UPDATE card_bill SET fin_request_no = ? WHERE card_bill_id = ?", requestNo(), unpaid);

        transaction.execute(status -> closer.closeAll(user));

        assertThat(billStatus(unpaid)).isEqualTo("CLOSED_BY_WITHDRAWAL");
    }

    @Test
    void 환급_입금을_요청하고_유예_안이면_409이고_아무것도_닫지_않는다() {
        long accumulating = bills.accumulating(user, card, DECEMBER, LocalDate.of(2026, 12, 15));
        long paid = bills.paid(user, card, NOVEMBER, LocalDate.of(2026, 11, 16), account, 10_000, "W1");
        long refund = refund(paid, 4_000, "PENDING");
        jdbc.update("UPDATE refund SET due_date = ?, fin_request_no = ? WHERE refund_id = ?", LocalDate.of(2026, 11, 19),
                requestNo(), refund);

        assertThatThrownBy(() -> transaction.execute(status -> closer.closeAll(user)))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.REFUND_PENDING);
                    assertThat(e.getDetails()).isEqualTo(Map.of("refundIds", List.of(refund)));
                });
        assertThat(billStatus(accumulating)).isEqualTo("ACCUMULATING");
        assertThat(refundStatus(refund)).isEqualTo("PENDING");

        clock.advance(Duration.ofSeconds(31));
        transaction.execute(status -> closer.closeAll(user));
        assertThat(refundStatus(refund)).isEqualTo("CLOSED_BY_WITHDRAWAL");
    }

    @Test
    void 기한_없는_대기_환급은_요청_번호가_없으므로_바로_닫는다() {
        long confirmed = bills.confirmed(user, card, NOVEMBER, LocalDate.of(2026, 11, 16), account, 30_000);
        long refund = refund(confirmed, 4_000, "PENDING");

        transaction.execute(status -> closer.closeAll(user));

        assertThat(refundStatus(refund)).isEqualTo("CLOSED_BY_WITHDRAWAL");
    }

    // ───────── 닫은 뒤 ─────────

    @Test
    void 닫은_청구서와_환급은_청구_확정_출금_환급_입금이_건너뛰고_금융망을_부르지_않는다() {
        long accumulating = bills.accumulating(user, card, OCTOBER, LocalDate.of(2026, 10, 15));
        long confirmed = bills.confirmed(user, card, NOVEMBER, LocalDate.of(2026, 11, 16), account, 30_000);
        long paid = bills.paid(user, card, YearMonth.of(2026, 9), LocalDate.of(2026, 9, 15), account, 10_000, "W1");
        long refund = refund(paid, 4_000, "PENDING");
        jdbc.update("UPDATE refund SET due_date = ? WHERE refund_id = ?", LocalDate.of(2026, 9, 18), refund);
        transaction.execute(status -> closer.closeAll(user));

        assertThat(confirmer.confirm(accumulating, LocalDateTime.now(ZoneOffset.UTC)).kind())
                .isEqualTo(BillConfirmer.Kind.ALREADY_DONE);
        assertThat(withdrawer.withdraw(confirmed, LocalDate.of(2026, 12, 31), false).kind())
                .isEqualTo(BillWithdrawer.Kind.SKIPPED);
        assertThat(depositor.deposit(refund).outcome()).isEqualTo(RefundDepositor.Outcome.SKIPPED);
        assertThat(billStatus(accumulating)).isEqualTo("CLOSED_BY_WITHDRAWAL");
        assertThat(billStatus(confirmed)).isEqualTo("CLOSED_BY_WITHDRAWAL");
        assertThat(refundStatus(refund)).isEqualTo("CLOSED_BY_WITHDRAWAL");
        verifyNoBankCalls();
    }

    @Test
    void 닫은_청구서의_결제를_취소해도_회차와_환급을_건드리지_않고_실패하지_않는다() {
        long confirmed = bills.confirmed(user, card, NOVEMBER, LocalDate.of(2026, 11, 16), account, 30_000);
        long payment = bills.payment(user, card, merchant, 30_000, 1, LocalDateTime.of(2026, 10, 20, 3, 0));
        long item = bills.item(payment, user, card, confirmed, 1, NOVEMBER, 30_000, "BILLED");
        transaction.execute(status -> closer.closeAll(user));

        CreditBillingCanceler.Result result = transaction.execute(status ->
                canceler.cancel(payments.findById(payment).orElseThrow(), LocalDateTime.now(ZoneOffset.UTC)));

        assertThat(result.refunds()).isEmpty();
        assertThat(result.billItems()).singleElement().satisfies(change -> {
            assertThat(change.before()).isEqualTo("BILLED");
            assertThat(change.after()).isEqualTo("BILLED");
        });
        assertThat(jdbc.queryForObject("SELECT status FROM bill_item WHERE bill_item_id = ?", String.class, item))
                .isEqualTo("BILLED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refund", Integer.class)).isZero();
        assertThat(billStatus(confirmed)).isEqualTo("CLOSED_BY_WITHDRAWAL");
    }

    // ───────── 도우미 ─────────

    /** 이 청구서에 회차 하나를 만들고 그 회차의 환급을 넣는다. */
    private long refund(long bill, long amount, String status) {
        long payment = bills.payment(user, card, merchant, amount, 1, LocalDateTime.of(2026, 8, 20, 3, 0));
        long item = bills.item(payment, user, card, bill, 1, YearMonth.of(2026, 9), amount, "REFUND_PENDING");
        return bills.refund(payment, item, user, account, amount, status);
    }

    /** 지금(가짜 시계) 채번한 기관거래고유번호. 앞 14자리가 한국 시각이다 */
    private String requestNo() {
        return REQUEST_TIME.format(clock.instant().atZone(KST)) + "000001";
    }

    private String billStatus(long bill) {
        return jdbc.queryForObject("SELECT status FROM card_bill WHERE card_bill_id = ?", String.class, bill);
    }

    private String refundStatus(long refund) {
        return jdbc.queryForObject("SELECT status FROM refund WHERE refund_id = ?", String.class, refund);
    }

    private void verifyNoBankCalls() {
        verify(transfer, never()).withdraw(anyString(), anyString(), anyLong(), any(), anyString());
        verify(transfer, never()).deposit(anyString(), anyString(), anyLong(), any(), anyString());
    }
}
