package com.yamu.be.payment.cancel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.yamu.be.global.calendar.BusinessDayCalendar;
import com.yamu.be.payment.billing.BillStatus;
import com.yamu.be.payment.billing.CardBillRepository;
import com.yamu.be.payment.billing.Refund;
import com.yamu.be.payment.billing.RefundRepository;
import com.yamu.be.payment.billing.RefundStatus;
import com.yamu.be.payment.domain.PaymentStatus;
import com.yamu.be.payment.domain.PaymentTransaction;
import com.yamu.be.payment.domain.PaymentTransactionRepository;
import com.yamu.be.support.AuthUsers;
import com.yamu.be.support.Concurrently;
import com.yamu.be.support.MySqlTestSupport;
import com.yamu.be.support.OwnershipFixtures;
import java.nio.ByteBuffer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;

/**
 * 결제 전체 취소(Task 5.3.2)를 실제 MySQL·Redis로 확인한다. 승인 흐름과 떼어 보려고 승인된 결제 행은 SQL로 직접 넣는다.
 *
 * <p>청구 회차·청구서도 SQL로 넣는다. 청구 확정(S15P31E103-206)·출금(S15P31E103-207) 전이라 확정·출금 완료·미납 청구서는
 * 직접 만들어 POL-006의 회차별 처리(제외·환급 대기·미납 원금 제외)를 본다. 실제 승인으로 만든 회차의 취소는
 * {@code CreditApprovalIntegrationTest}가 본다.
 * 실적·한도 복구는 원장 반영(Task 5.2.5) 전이라 응답의 {@code restored}는 비어 있다.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.application.name=yamu-payment-cancel-test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PaymentCancelIntegrationTest extends MySqlTestSupport {

    static final LocalDate DECEMBER = LocalDate.of(2026, 12, 1);
    static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Container
    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired PaymentTransactionRepository payments;
    @Autowired RefundRepository refunds;
    @Autowired BusinessDayCalendar calendar;
    @Autowired CardBillRepository bills;
    @Autowired CanceledBillItemSettler settler;
    @Autowired TransactionTemplate transaction;
    @MockitoSpyBean CancelIdempotencyStore idempotency;

    OwnershipFixtures fixtures;
    long user;
    long otherUser;
    long account;
    long creditCard;
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
        fixtures = new OwnershipFixtures(jdbc);
        user = fixtures.user();
        otherUser = fixtures.user();
        account = fixtures.account(user);
        creditCard = fixtures.card(user, fixtures.product(), account, "ACTIVE");
        merchant = merchant();
    }

    // ───────── 취소 성공 ─────────

    @Test
    void 승인된_신용카드_결제를_취소하면_CANCELED와_취소_시각이_남고_승인_당시_값은_그대로다() throws Exception {
        long paymentId = approved(user, creditCard, "YAMU");

        cancel(paymentId, UUID.randomUUID().toString(), "{\"reason\": \"고객 요청\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paymentId").value(paymentId))
                .andExpect(jsonPath("$.data.status").value("CANCELED"))
                .andExpect(jsonPath("$.data.canceledAt").isString())
                .andExpect(jsonPath("$.data.restored.performance").doesNotExist())
                .andExpect(jsonPath("$.data.restored.limitImpacts.length()").value(0))
                .andExpect(jsonPath("$.data.billItems.length()").value(0))
                .andExpect(jsonPath("$.data.refunds.length()").value(0))
                .andExpect(jsonPath("$.data.accountBalanceAfter").doesNotExist());

        PaymentTransaction payment = payments.findById(paymentId).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELED);
        assertThat(payment.getCanceledAt()).isNotNull();
        // 승인 당시 값은 지우지 않는다. 실적 복구는 원장 반영 전이라 아직 하지 않았으므로 복구 시각도 없다
        assertThat(payment.getApprovedAmount()).isEqualTo(15_000L);
        assertThat(payment.getRecognizedPerformanceAmount()).isEqualTo(15_000L);
        assertThat(payment.getPerformanceMonth()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(payment.getPerformanceReversedAt()).isNull();
    }

    @Test
    void 본문_없이도_취소된다() throws Exception {
        long paymentId = approved(user, creditCard, "YAMU");

        cancel(paymentId, UUID.randomUUID().toString(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELED"));
    }

    @Test
    void 등록_해제한_카드의_결제도_취소된다() throws Exception {
        long paymentId = approved(user, creditCard, "YAMU");
        jdbc.update("UPDATE user_card SET status = 'UNREGISTERED' WHERE user_card_id = ?", creditCard);

        cancel(paymentId, UUID.randomUUID().toString(), "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELED"));
    }

    // ───────── 재시도·동시 요청 ─────────

    @Test
    void 취소_뒤_응답_기억이_실패해도_취소_결과를_그대로_돌려준다() throws Exception {
        long paymentId = approved(user, creditCard, "YAMU");
        doThrow(new RedisConnectionFailureException("Redis 끊김")).when(idempotency).remember(any(), any(), any());

        cancel(paymentId, UUID.randomUUID().toString(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paymentId").value(paymentId))
                .andExpect(jsonPath("$.data.status").value("CANCELED"));

        assertThat(payments.findById(paymentId).orElseThrow().getStatus()).isEqualTo(PaymentStatus.CANCELED);
    }

    @Test
    void 같은_키로_다시_보내면_처음_응답을_그대로_돌려준다() throws Exception {
        long paymentId = approved(user, creditCard, "YAMU");
        String key = UUID.randomUUID().toString();

        String first = cancel(paymentId, key, "{\"reason\": \"고객 요청\"}")
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String second = cancel(paymentId, key, "{\"reason\": \"고객 요청\"}")
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(second).isEqualTo(first);
    }

    @Test
    void 같은_키에_다른_사유를_보내면_409_IDEMPOTENCY_KEY_REUSED() throws Exception {
        long paymentId = approved(user, creditCard, "YAMU");
        String key = UUID.randomUUID().toString();
        cancel(paymentId, key, "{\"reason\": \"고객 요청\"}").andExpect(status().isOk());

        cancel(paymentId, key, "{\"reason\": \"단순 변심\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void 다른_키로_다시_취소하면_409_ALREADY_CANCELED() throws Exception {
        long paymentId = approved(user, creditCard, "YAMU");
        cancel(paymentId, UUID.randomUUID().toString(), null).andExpect(status().isOk());

        cancel(paymentId, UUID.randomUUID().toString(), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ALREADY_CANCELED"));
    }

    @Test
    void 다른_키로_동시에_취소하면_한_건만_성공하고_나머지는_ALREADY_CANCELED() throws Exception {
        long paymentId = approved(user, creditCard, "YAMU");
        long itemId = billItem(paymentId, bill("ACCUMULATING", DECEMBER), 1, "PENDING", 15_000);

        List<String> results = Concurrently.run(3, () -> {
            String body = cancel(paymentId, UUID.randomUUID().toString(), null).andReturn().getResponse()
                    .getContentAsString();
            return body.contains("\"success\":true") ? "OK" : body.contains("ALREADY_CANCELED") ? "ALREADY" : body;
        });

        assertThat(results).containsExactlyInAnyOrder("OK", "ALREADY", "ALREADY");
        assertThat(payments.findById(paymentId).orElseThrow().getStatus()).isEqualTo(PaymentStatus.CANCELED);
        assertThat(itemStatus(itemId)).isEqualTo("EXCLUDED");
        assertThat(itemVersion(itemId)).isEqualTo(1L);
    }

    // ───────── 청구 회차 ─────────

    @Test
    void 청구_전_일시불_회차는_EXCLUDED가_되고_환급은_만들지_않으며_청구서는_그대로다() throws Exception {
        long paymentId = approved(user, creditCard, "YAMU");
        long billId = bill("ACCUMULATING", DECEMBER);
        long itemId = billItem(paymentId, billId, 1, "PENDING", 15_000);

        cancel(paymentId, UUID.randomUUID().toString(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELED"))
                .andExpect(jsonPath("$.data.billItems.length()").value(1))
                .andExpect(jsonPath("$.data.billItems[0].billItemId").value(itemId))
                .andExpect(jsonPath("$.data.billItems[0].seq").value(1))
                .andExpect(jsonPath("$.data.billItems[0].amount").value(15_000))
                .andExpect(jsonPath("$.data.billItems[0].before").value("PENDING"))
                .andExpect(jsonPath("$.data.billItems[0].after").value("EXCLUDED"))
                .andExpect(jsonPath("$.data.refunds.length()").value(0));

        assertThat(itemStatus(itemId)).isEqualTo("EXCLUDED");
        assertThat(refunds.findByPaymentTransactionIdOrderByBillItemIdAsc(paymentId)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT status FROM card_bill WHERE card_bill_id = ?", String.class, billId))
                .isEqualTo("ACCUMULATING");
    }

    @Test
    void 할부_회차는_모두_EXCLUDED가_되고_응답은_회차_순이다() throws Exception {
        long paymentId = approved(user, creditCard, "YAMU");
        // 회차 번호와 저장 순서를 엇갈리게 넣는다
        long third = billItem(paymentId, bill("ACCUMULATING", DECEMBER.plusMonths(2)), 3, "PENDING", 33_334);
        long first = billItem(paymentId, bill("ACCUMULATING", DECEMBER), 1, "PENDING", 33_333);
        long second = billItem(paymentId, bill("ACCUMULATING", DECEMBER.plusMonths(1)), 2, "PENDING", 33_333);

        cancel(paymentId, UUID.randomUUID().toString(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.billItems.length()").value(3))
                .andExpect(jsonPath("$.data.billItems[0].billItemId").value(first))
                .andExpect(jsonPath("$.data.billItems[0].seq").value(1))
                .andExpect(jsonPath("$.data.billItems[1].billItemId").value(second))
                .andExpect(jsonPath("$.data.billItems[1].seq").value(2))
                .andExpect(jsonPath("$.data.billItems[2].billItemId").value(third))
                .andExpect(jsonPath("$.data.billItems[2].seq").value(3))
                .andExpect(jsonPath("$.data.billItems[2].amount").value(33_334))
                .andExpect(jsonPath("$.data.billItems[2].after").value("EXCLUDED"))
                .andExpect(jsonPath("$.data.refunds.length()").value(0));

        assertThat(List.of(itemStatus(first), itemStatus(second), itemStatus(third)))
                .containsOnly("EXCLUDED");
    }

    @Test
    void 할부_1회차_출금_뒤_취소하면_출금분과_확정분은_환급_대기이고_청구_전_회차는_제외한다() throws Exception {
        // 30만 원 3개월: 1회차는 출금 완료, 2회차는 확정·출금 전, 3회차는 청구 전
        long paymentId = approved(user, creditCard, "YAMU");
        long paidBill = bill("PAID", DECEMBER, 100_000);
        long confirmedBill = bill("CONFIRMED", DECEMBER.plusMonths(1), 100_000);
        long accumulatingBill = bill("ACCUMULATING", DECEMBER.plusMonths(2), 0);
        long first = billItem(paymentId, paidBill, 1, "PAID", 100_000);
        long second = billItem(paymentId, confirmedBill, 2, "BILLED", 100_000);
        long third = billItem(paymentId, accumulatingBill, 3, "PENDING", 100_000);
        LocalDate expectedDue = calendar.plusBusinessDays(LocalDate.now(KST), 3);

        cancel(paymentId, UUID.randomUUID().toString(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELED"))
                .andExpect(jsonPath("$.data.billItems.length()").value(3))
                .andExpect(jsonPath("$.data.billItems[0].before").value("PAID"))
                .andExpect(jsonPath("$.data.billItems[0].after").value("REFUND_PENDING"))
                .andExpect(jsonPath("$.data.billItems[1].before").value("BILLED"))
                .andExpect(jsonPath("$.data.billItems[1].after").value("REFUND_PENDING"))
                .andExpect(jsonPath("$.data.billItems[2].before").value("PENDING"))
                .andExpect(jsonPath("$.data.billItems[2].after").value("EXCLUDED"))
                .andExpect(jsonPath("$.data.refunds.length()").value(2))
                .andExpect(jsonPath("$.data.refunds[0].billItemId").value(first))
                .andExpect(jsonPath("$.data.refunds[0].amount").value(100_000))
                .andExpect(jsonPath("$.data.refunds[0].status").value("PENDING"))
                .andExpect(jsonPath("$.data.refunds[0].dueDate").value(expectedDue.toString()))
                .andExpect(jsonPath("$.data.refunds[0].toAccountId").value(account))
                .andExpect(jsonPath("$.data.refunds[1].billItemId").value(second))
                .andExpect(jsonPath("$.data.refunds[1].amount").value(100_000))
                .andExpect(jsonPath("$.data.refunds[1].dueDate").doesNotExist())
                .andExpect(jsonPath("$.data.refunds[1].toAccountId").value(account));

        assertThat(List.of(itemStatus(first), itemStatus(second), itemStatus(third)))
                .containsExactly("REFUND_PENDING", "REFUND_PENDING", "EXCLUDED");
        List<Refund> saved = refunds.findByPaymentTransactionIdOrderByBillItemIdAsc(paymentId);
        assertThat(saved).extracting(Refund::getBillItemId).containsExactly(first, second);
        assertThat(saved).extracting(Refund::getRefundAmount).containsExactly(100_000L, 100_000L);
        assertThat(saved).extracting(Refund::getFeeAmount).containsExactly(0L, 0L);
        assertThat(saved).extracting(Refund::getLinkedAccountId).containsOnly(account);
        assertThat(saved).extracting(Refund::getStatus).containsOnly(RefundStatus.PENDING);
        assertThat(saved.get(0).getDueDate()).isEqualTo(expectedDue).isAfter(LocalDate.now(KST));
        assertThat(saved.get(1).getDueDate()).isNull();
        // 확정 청구 원본은 그대로 두고 출금도 그대로 한다
        assertThat(billRow(paidBill)).containsEntry("status", "PAID").containsEntry("confirmed_amount", 100_000L);
        assertThat(billRow(confirmedBill)).containsEntry("status", "CONFIRMED").containsEntry("confirmed_amount", 100_000L);
    }

    @Test
    void 미납_회차는_환급_없이_납부_대상에서_빼고_같은_청구서의_다른_결제_미납금은_남긴다() throws Exception {
        // 확정 10만 원이 출금 실패로 미납: 이 결제 4만 원 + 다른 결제 6만 원
        long paymentId = approved(user, creditCard, "YAMU");
        long otherPayment = approved(user, creditCard, "YAMU");
        long unpaidBill = bill("UNPAID", DECEMBER, 100_000);
        long mine = billItem(paymentId, unpaidBill, 1, "BILLED", 40_000);
        long others = billItem(otherPayment, unpaidBill, 1, "BILLED", 60_000);

        cancel(paymentId, UUID.randomUUID().toString(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.billItems[0].before").value("BILLED"))
                .andExpect(jsonPath("$.data.billItems[0].after").value("EXCLUDED"))
                .andExpect(jsonPath("$.data.billItems[0].amount").value(40_000))
                .andExpect(jsonPath("$.data.refunds.length()").value(0));

        assertThat(itemStatus(mine)).isEqualTo("EXCLUDED");
        assertThat(jdbc.queryForObject("SELECT unpaid_principal_waived_at FROM bill_item WHERE bill_item_id = ?",
                LocalDateTime.class, mine)).isNotNull();
        assertThat(itemStatus(others)).isEqualTo("BILLED");
        assertThat(refunds.findByPaymentTransactionIdOrderByBillItemIdAsc(paymentId)).isEmpty();
        assertThat(billRow(unpaidBill))
                .containsEntry("status", "UNPAID")
                .containsEntry("confirmed_amount", 100_000L)
                .containsEntry("paid_amount", 0L)
                .containsEntry("canceled_unpaid_principal", 40_000L);
    }

    @Test
    void 다른_키로_동시에_취소해도_출금_회차의_환급은_한_번만_생긴다() throws Exception {
        long paymentId = approved(user, creditCard, "YAMU");
        billItem(paymentId, bill("PAID", DECEMBER, 15_000), 1, "PAID", 15_000);

        List<String> results = Concurrently.run(3, () -> {
            String body = cancel(paymentId, UUID.randomUUID().toString(), null).andReturn().getResponse()
                    .getContentAsString();
            return body.contains("\"success\":true") ? "OK" : body.contains("ALREADY_CANCELED") ? "ALREADY" : body;
        });

        assertThat(results).containsExactlyInAnyOrder("OK", "ALREADY", "ALREADY");
        assertThat(refunds.findByPaymentTransactionIdOrderByBillItemIdAsc(paymentId)).hasSize(1);
    }

    @Test
    void 청구서와_회차_상태가_맞지_않으면_취소하지_않고_결제_회차_환급을_그대로_둔다() throws Exception {
        // 출금 완료 청구서에 출금 전 회차: 청구 확정·출금 기록이 어긋난 데이터
        long paymentId = approved(user, creditCard, "YAMU");
        long refundable = billItem(paymentId, bill("PAID", DECEMBER, 15_000), 1, "PAID", 15_000);
        long broken = billItem(paymentId, bill("PAID", DECEMBER.plusMonths(1), 15_000), 2, "BILLED", 15_000);

        cancel(paymentId, UUID.randomUUID().toString(), null)
                .andExpect(status().isInternalServerError());

        PaymentTransaction payment = payments.findById(paymentId).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(payment.getCanceledAt()).isNull();
        assertThat(itemStatus(refundable)).isEqualTo("PAID");
        assertThat(itemStatus(broken)).isEqualTo("BILLED");
        assertThat(refunds.findByPaymentTransactionIdOrderByBillItemIdAsc(paymentId)).isEmpty();
    }

    // ───────── 환급 금액 ─────────

    @Test
    void 청구할인처럼_확정액이_회차_청구액_합과_다르면_원금으로_환급하지_않고_롤백한다() throws Exception {
        // 원금 10만 원에 청구할인 1만 원이 적용돼 9만 원이 출금됐다. 할인의 회차 배분 기록이 없어 회차 몫을 알 수 없다
        long paymentId = approved(user, creditCard, "YAMU");
        long item = billItem(paymentId, bill("PAID", DECEMBER, 90_000), 1, "PAID", 100_000);

        cancel(paymentId, UUID.randomUUID().toString(), null)
                .andExpect(status().isInternalServerError());

        assertThat(payments.findById(paymentId).orElseThrow().getStatus()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(itemStatus(item)).isEqualTo("PAID");
        assertThat(refunds.findByPaymentTransactionIdOrderByBillItemIdAsc(paymentId)).isEmpty();
    }

    @Test
    void 회차_몫이_0원이면_환급_행_없이_회차만_닫고_취소는_성공한다() throws Exception {
        // 1원 할부처럼 회차 원금이 0원인 회차. 같은 청구서의 다른 결제가 확정액 전부를 차지한다
        long paymentId = approved(user, creditCard, "YAMU");
        long otherPayment = approved(user, creditCard, "YAMU");
        long paidBill = bill("PAID", DECEMBER, 15_000);
        long zero = billItem(paymentId, paidBill, 1, "PAID", 0);
        billItem(otherPayment, paidBill, 1, "PAID", 15_000);

        cancel(paymentId, UUID.randomUUID().toString(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELED"))
                .andExpect(jsonPath("$.data.billItems[0].before").value("PAID"))
                .andExpect(jsonPath("$.data.billItems[0].after").value("EXCLUDED"))
                .andExpect(jsonPath("$.data.refunds.length()").value(0));

        assertThat(itemStatus(zero)).isEqualTo("EXCLUDED");
        // 확정액에 들어간 회차라 제외 시각을 남긴다(확정 전 취소와 가르는 표시. 청구서 조회 건수·회차 몫 계산이 쓴다)
        assertThat(jdbc.queryForObject("SELECT unpaid_principal_waived_at FROM bill_item WHERE bill_item_id = ?",
                LocalDateTime.class, zero)).isNotNull();
        assertThat(refunds.findByPaymentTransactionIdOrderByBillItemIdAsc(paymentId)).isEmpty();
    }

    @Test
    void 취소_전_결제의_회차에_이미_환급이_있으면_다시_만들지_않고_롤백한다() throws Exception {
        long paymentId = approved(user, creditCard, "YAMU");
        long item = billItem(paymentId, bill("PAID", DECEMBER, 15_000), 1, "PAID", 15_000);
        refunds.saveAndFlush(Refund.pending(paymentId, item, user, account, 15_000, LocalDate.now(KST).plusDays(5)));

        cancel(paymentId, UUID.randomUUID().toString(), null)
                .andExpect(status().isInternalServerError());

        assertThat(payments.findById(paymentId).orElseThrow().getStatus()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(refunds.findByPaymentTransactionIdOrderByBillItemIdAsc(paymentId)).hasSize(1);
    }

    // ───────── 출금 전 취소 뒤 출금 결과 ─────────

    @Test
    void 출금_전_취소_뒤_출금이_성공하면_실제_출금된_몫과_출금일_3영업일_뒤_기한으로_환급을_확정한다() throws Exception {
        long paymentId = approved(user, creditCard, "YAMU");
        long otherPayment = approved(user, creditCard, "YAMU");
        long confirmedBill = bill("CONFIRMED", DECEMBER, 15_000);
        long mine = billItem(paymentId, confirmedBill, 1, "BILLED", 10_000);
        long others = billItem(otherPayment, confirmedBill, 1, "BILLED", 5_000);
        cancel(paymentId, UUID.randomUUID().toString(), null).andExpect(status().isOk());
        Refund waiting = refunds.findByPaymentTransactionIdOrderByBillItemIdAsc(paymentId).get(0);
        assertThat(waiting.isAwaitingWithdrawal()).isTrue();
        assertThat(waiting.getRefundAmount()).isEqualTo(10_000L);

        LocalDate withdrawnOn = LocalDate.of(2026, 12, 15);
        List<Refund> confirmed = withdrawalSucceeded(confirmedBill, withdrawnOn);

        assertThat(confirmed).extracting(Refund::getId).containsExactly(waiting.getId());
        Refund read = refunds.findById(waiting.getId()).orElseThrow();
        assertThat(read.isAwaitingWithdrawal()).isFalse();
        assertThat(read.getStatus()).isEqualTo(RefundStatus.PENDING);
        assertThat(read.getRefundAmount()).isEqualTo(10_000L);
        assertThat(read.getDueDate()).isEqualTo(calendar.plusBusinessDays(withdrawnOn, 3));
        assertThat(read.getLinkedAccountId()).isEqualTo(account);
        assertThat(itemStatus(mine)).isEqualTo("REFUND_PENDING");
        assertThat(itemStatus(others)).isEqualTo("PAID");
    }

    @Test
    void 출금_전_취소_뒤_출금이_실패하면_환급을_무효로_하고_그_몫만_미납에서_뺀다() throws Exception {
        long paymentId = approved(user, creditCard, "YAMU");
        long otherPayment = approved(user, creditCard, "YAMU");
        long confirmedBill = bill("CONFIRMED", DECEMBER, 15_000);
        long mine = billItem(paymentId, confirmedBill, 1, "BILLED", 10_000);
        long others = billItem(otherPayment, confirmedBill, 1, "BILLED", 5_000);
        cancel(paymentId, UUID.randomUUID().toString(), null).andExpect(status().isOk());
        Refund waiting = refunds.findByPaymentTransactionIdOrderByBillItemIdAsc(paymentId).get(0);

        List<Refund> voided = withdrawalFailed(confirmedBill);

        assertThat(voided).extracting(Refund::getId).containsExactly(waiting.getId());
        assertThat(refunds.findById(waiting.getId()).orElseThrow().getStatus()).isEqualTo(RefundStatus.CANCELED);
        assertThat(itemStatus(mine)).isEqualTo("EXCLUDED");
        assertThat(jdbc.queryForObject("SELECT unpaid_principal_waived_at FROM bill_item WHERE bill_item_id = ?",
                LocalDateTime.class, mine)).isNotNull();
        assertThat(itemStatus(others)).isEqualTo("BILLED");
        assertThat(billRow(confirmedBill))
                .containsEntry("status", "UNPAID")
                .containsEntry("confirmed_amount", 15_000L)
                .containsEntry("canceled_unpaid_principal", 10_000L);
        // 다른 결제의 미납 5천 원이 남아 청구서는 UNPAID 그대로이고 이 카드는 계속 미납이다
        assertThat(bills.existsByUserCardIdAndStatus(creditCard, BillStatus.UNPAID)).isTrue();
    }

    @Test
    void 출금_전에_청구서의_결제를_모두_취소했는데_출금이_실패하면_남은_미납액이_0원이라_청구서가_CANCELED가_된다() throws Exception {
        long paymentId = approved(user, creditCard, "YAMU");
        long confirmedBill = bill("CONFIRMED", DECEMBER, 15_000);
        long mine = billItem(paymentId, confirmedBill, 1, "BILLED", 15_000);
        cancel(paymentId, UUID.randomUUID().toString(), null).andExpect(status().isOk());

        withdrawalFailed(confirmedBill);

        assertThat(itemStatus(mine)).isEqualTo("EXCLUDED");
        assertThat(billRow(confirmedBill))
                .containsEntry("status", "CANCELED")
                .containsEntry("confirmed_amount", 15_000L)
                .containsEntry("paid_amount", 0L)
                .containsEntry("canceled_unpaid_principal", 15_000L);
        assertThat(failedAt(confirmedBill)).isNotNull();
        assertThat(bills.existsByUserCardIdAndStatus(creditCard, BillStatus.UNPAID)).isFalse();
    }

    @Test
    void 출금_결과_불명이면_환급은_출금_대기로_남고_결과_확정_처리를_미리_할_수_없다() throws Exception {
        long paymentId = approved(user, creditCard, "YAMU");
        long confirmedBill = bill("CONFIRMED", DECEMBER, 15_000);
        billItem(paymentId, confirmedBill, 1, "BILLED", 15_000);
        cancel(paymentId, UUID.randomUUID().toString(), null).andExpect(status().isOk());
        // 출금 요청을 보냈지만 응답을 받지 못했다: 청구서는 CONFIRMED 그대로 요청 번호만 남는다
        jdbc.update("UPDATE card_bill SET fin_request_no = '20261215070000000001' WHERE card_bill_id = ?", confirmedBill);

        assertThatThrownBy(() -> transaction.execute(status -> settler.onWithdrawalSucceeded(confirmedBill, account,
                LocalDate.of(2026, 12, 15)))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> transaction.execute(status -> settler.onWithdrawalFailed(confirmedBill,
                LocalDateTime.now(ZoneOffset.UTC)))).isInstanceOf(IllegalStateException.class);

        Refund waiting = refunds.findByPaymentTransactionIdOrderByBillItemIdAsc(paymentId).get(0);
        assertThat(waiting.isAwaitingWithdrawal()).isTrue();
        assertThat(waiting.getStatus()).isEqualTo(RefundStatus.PENDING);
        assertThat(billRow(confirmedBill)).containsEntry("status", "CONFIRMED");
    }

    @Test
    void 출금_결과_저장과_취소가_동시에_와도_환급은_한_번만_생기고_금액과_기한이_정해진다() throws Exception {
        long paymentId = approved(user, creditCard, "YAMU");
        long confirmedBill = bill("CONFIRMED", DECEMBER, 15_000);
        long mine = billItem(paymentId, confirmedBill, 1, "BILLED", 15_000);
        LocalDate today = LocalDate.now(KST);

        List<String> results = Concurrently.run(List.<Callable<String>>of(
                () -> cancel(paymentId, UUID.randomUUID().toString(), null).andReturn().getResponse().getStatus() + "",
                () -> {
                    withdrawalSucceeded(confirmedBill, today);
                    return "WITHDRAWN";
                }));

        assertThat(results).containsExactly("200", "WITHDRAWN");
        List<Refund> saved = refunds.findByPaymentTransactionIdOrderByBillItemIdAsc(paymentId);
        assertThat(saved).hasSize(1);
        // 어느 쪽이 먼저 잠그든 결과가 같다: 출금 뒤 취소면 취소일 + 3영업일, 취소 뒤 출금이면 출금일 + 3영업일(둘 다 오늘)
        assertThat(saved.get(0).getRefundAmount()).isEqualTo(15_000L);
        assertThat(saved.get(0).getDueDate()).isEqualTo(calendar.plusBusinessDays(today, 3));
        assertThat(itemStatus(mine)).isEqualTo("REFUND_PENDING");
        assertThat(billRow(confirmedBill)).containsEntry("status", "PAID");
    }

    // ───────── 미납 0원 ─────────

    @Test
    void 미납_결제를_모두_취소하면_청구서가_CANCELED가_되고_확정_원본과_출금_실패_이력은_남는다() throws Exception {
        long paymentId = approved(user, creditCard, "YAMU");
        long unpaidBill = bill("UNPAID", DECEMBER, 40_000);
        billItem(paymentId, unpaidBill, 1, "BILLED", 40_000);

        cancel(paymentId, UUID.randomUUID().toString(), null).andExpect(status().isOk());

        // 낼 돈이 없으니 미납이 아니고, 출금도 없었으니 PAID도 아니다
        assertThat(billRow(unpaidBill))
                .containsEntry("status", "CANCELED")
                .containsEntry("confirmed_amount", 40_000L)
                .containsEntry("paid_amount", 0L)
                .containsEntry("canceled_unpaid_principal", 40_000L);
        assertThat(failedAt(unpaidBill)).isNotNull();
        assertThat(bills.existsByUserCardIdAndStatus(creditCard, BillStatus.UNPAID)).isFalse();

        // 다른 청구서에 미납이 남으면 그 카드는 여전히 미납이다
        long otherPayment = approved(user, creditCard, "YAMU");
        billItem(otherPayment, bill("UNPAID", DECEMBER.plusMonths(1), 20_000), 1, "BILLED", 20_000);
        assertThat(bills.existsByUserCardIdAndStatus(creditCard, BillStatus.UNPAID)).isTrue();
    }

    @Test
    void 미납이_정리된_청구서에_남은_0원_회차의_결제도_취소된다() throws Exception {
        // 4만 원 미납 청구서에 다른 결제의 0원 회차(1원 할부의 앞 회차 등)가 함께 있다
        long paymentId = approved(user, creditCard, "YAMU");
        long zeroPayment = approved(user, creditCard, "YAMU");
        long unpaidBill = bill("UNPAID", DECEMBER, 40_000);
        billItem(paymentId, unpaidBill, 1, "BILLED", 40_000);
        long zero = billItem(zeroPayment, unpaidBill, 1, "BILLED", 0);
        cancel(paymentId, UUID.randomUUID().toString(), null).andExpect(status().isOk());
        assertThat(billRow(unpaidBill)).containsEntry("status", "CANCELED");

        cancel(zeroPayment, UUID.randomUUID().toString(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.billItems[0].before").value("BILLED"))
                .andExpect(jsonPath("$.data.billItems[0].after").value("EXCLUDED"))
                .andExpect(jsonPath("$.data.refunds.length()").value(0));

        assertThat(itemStatus(zero)).isEqualTo("EXCLUDED");
        assertThat(billRow(unpaidBill))
                .containsEntry("status", "CANCELED")
                .containsEntry("canceled_unpaid_principal", 40_000L);
    }

    // ───────── 취소할 수 없는 결제 ─────────

    @Test
    void 남의_결제와_없는_결제는_404() throws Exception {
        long others = approved(otherUser, fixtures.card(otherUser, fixtures.product(), null, "ACTIVE"), "YAMU");

        cancel(others, UUID.randomUUID().toString(), null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        cancel(999_999L, UUID.randomUUID().toString(), null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        assertThat(payments.findById(others).orElseThrow().getStatus()).isEqualTo(PaymentStatus.APPROVED);
    }

    @Test
    void 승인되지_않은_결제는_409_PAYMENT_NOT_CANCELABLE() throws Exception {
        long requested = requested(user);

        cancel(requested, UUID.randomUUID().toString(), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PAYMENT_NOT_CANCELABLE"))
                .andExpect(jsonPath("$.error.details.reason").value("NOT_APPROVED"))
                .andExpect(jsonPath("$.error.details.status").value("REQUESTED"));
        assertThat(payments.findById(requested).orElseThrow().getStatus()).isEqualTo(PaymentStatus.REQUESTED);
    }

    @Test
    void SEED_거래는_409_PAYMENT_NOT_CANCELABLE() throws Exception {
        long seed = approved(user, creditCard, "SEED");

        cancel(seed, UUID.randomUUID().toString(), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PAYMENT_NOT_CANCELABLE"))
                .andExpect(jsonPath("$.error.details.reason").value("SEED_TRANSACTION"));
        assertThat(payments.findById(seed).orElseThrow().getStatus()).isEqualTo(PaymentStatus.APPROVED);
    }

    // ───────── 잘못된 요청 ─────────

    @Test
    void 취소_금액을_보내면_400_PARTIAL_CANCEL_NOT_SUPPORTED() throws Exception {
        long paymentId = approved(user, creditCard, "YAMU");

        cancel(paymentId, UUID.randomUUID().toString(), "{\"amount\": 5000}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("PARTIAL_CANCEL_NOT_SUPPORTED"))
                .andExpect(jsonPath("$.error.details.fieldErrors[0].field").value("amount"));
        assertThat(payments.findById(paymentId).orElseThrow().getStatus()).isEqualTo(PaymentStatus.APPROVED);
    }

    @Test
    void 사유가_문자열이_아니거나_100자를_넘으면_400_INVALID_REQUEST() throws Exception {
        long paymentId = approved(user, creditCard, "YAMU");

        cancel(paymentId, UUID.randomUUID().toString(), "{\"reason\": 1}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.details.fieldErrors[0].field").value("reason"));
        cancel(paymentId, UUID.randomUUID().toString(), "{\"reason\": \"" + "가".repeat(101) + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void Idempotency_Key가_없거나_UUID가_아니면_400() throws Exception {
        long paymentId = approved(user, creditCard, "YAMU");

        cancel(paymentId, null, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        cancel(paymentId, "not-a-uuid", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REQUIRED"));
    }

    // ───────── 환급 엔티티 ─────────

    @Test
    void 환급은_회차당_한_행이고_수수료_0원으로_원금과_같은_금액을_적는다() {
        long paymentId = approved(user, creditCard, "YAMU");
        long itemId = billItem(paymentId, bill("ACCUMULATING", DECEMBER), 1, "PENDING", 15_000);

        Refund saved = refunds.saveAndFlush(Refund.pending(paymentId, itemId, user, account, 15_000, LocalDate.of(2026, 11, 6)));

        Refund read = refunds.findById(saved.getId()).orElseThrow();
        assertThat(read.getStatus()).isEqualTo(RefundStatus.PENDING);
        assertThat(read.getFeeAmount()).isZero();
        assertThat(read.getRefundAmount()).isEqualTo(15_000L);
        assertThat(read.getRequestKey()).hasSize(36);
        assertThat(refunds.findByPaymentTransactionIdOrderByBillItemIdAsc(paymentId)).hasSize(1);
        Refund duplicate = Refund.pending(paymentId, itemId, user, account, 15_000, null);
        assertThatThrownBy(() -> refunds.saveAndFlush(duplicate)).isInstanceOf(DataIntegrityViolationException.class);
    }

    // ───────── 도우미 ─────────

    private ResultActions cancel(long paymentId, String idempotencyKey, String body) throws Exception {
        MockHttpServletRequestBuilder builder = post("/api/payments/{paymentId}/cancel", paymentId)
                .with(AuthUsers.asUser(user));
        if (body != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        if (idempotencyKey != null) {
            builder.header("Idempotency-Key", idempotencyKey);
        }
        return mockMvc.perform(builder);
    }

    /** 승인된 15,000원 일시불 결제 행. source는 YAMU 또는 SEED. */
    private long approved(long owner, long userCardId, String source) {
        Long productId = jdbc.queryForObject("SELECT card_product_id FROM user_card WHERE user_card_id = ?", Long.class,
                userCardId);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Map<String, Object> row = paymentRow(owner, source, "APPROVED");
        row.put("user_card_id", userCardId);
        row.put("card_product_id", productId);
        row.put("approved_amount", 15_000L);
        row.put("recognized_performance_amount", 15_000L);
        row.put("performance_month", LocalDate.of(2026, 11, 1));
        row.put("approved_at", now);
        return insert("payment_transaction", "payment_transaction_id", row);
    }

    /** 카드 선택 전 REQUESTED 결제 행. */
    private long requested(long owner) {
        return insert("payment_transaction", "payment_transaction_id", paymentRow(owner, "YAMU", "REQUESTED"));
    }

    private Map<String, Object> paymentRow(long owner, String source, String status) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        UUID a = UUID.randomUUID();
        Map<String, Object> row = new HashMap<>();
        row.put("user_id", owner);
        row.put("merchant_id", merchant);
        row.put("request_key", UUID.randomUUID().toString());
        row.put("request_fingerprint", ByteBuffer.allocate(32).putLong(a.getMostSignificantBits())
                .putLong(a.getLeastSignificantBits()).array());
        row.put("source", source);
        row.put("status", status);
        row.put("requested_amount", 15_000L);
        row.put("installment_months", 1);
        row.put("requested_at", now);
        row.put("created_at", now);
        row.put("updated_at", now);
        return row;
    }

    private long bill(String status, LocalDate billingMonth) {
        return bill(status, billingMonth, 15_000);
    }

    /**
     * 신용카드의 청구서. 날짜는 결제일 15일·마감 14일 기준의 모양만 맞춘다. 청구 확정(S15P31E103-206)·출금(S15P31E103-207) 전이라
     * 확정 이후 상태는 확정·출금·실패 칸까지 직접 채운다. PAID는 확정액 전부를 출금한 것으로, UNPAID는 한 푼도 못 받은 것으로 둔다.
     */
    private long bill(String status, LocalDate billingMonth, long confirmedAmount) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Map<String, Object> row = new HashMap<>();
        row.put("user_id", user);
        row.put("user_card_id", creditCard);
        row.put("billing_month", billingMonth);
        row.put("period_start", billingMonth.minusMonths(1).withDayOfMonth(2));
        row.put("period_end", billingMonth.withDayOfMonth(1));
        row.put("withdrawal_date", billingMonth.withDayOfMonth(15));
        row.put("status", status);
        if (!status.equals("ACCUMULATING")) {
            row.put("linked_account_id", account);
            row.put("confirmed_amount", confirmedAmount);
            row.put("confirmed_at", now);
        }
        if (status.equals("PAID")) {
            row.put("paid_amount", confirmedAmount);
            row.put("paid_at", now);
        }
        if (status.equals("UNPAID")) {
            row.put("failed_at", now);
        }
        row.put("created_at", now);
        row.put("updated_at", now);
        return insert("card_bill", "card_bill_id", row);
    }

    /**
     * 결제일 출금 성공 저장(S15P31E103-207이 할 일의 자리). 청구서를 잠그고 확정액 전부 출금으로 적은 뒤, 취소되지 않은 회차를 PAID로
     * 바꾸고 같은 트랜잭션에서 취소 회차 정리를 부른다. 실제 출금 배치가 아니다.
     */
    private List<Refund> withdrawalSucceeded(long cardBillId, LocalDate withdrawnOn) {
        return transaction.execute(status -> {
            jdbc.queryForObject("SELECT card_bill_id FROM card_bill WHERE card_bill_id = ? FOR UPDATE", Long.class, cardBillId);
            jdbc.update("""
                    UPDATE card_bill SET status = 'PAID', paid_amount = confirmed_amount, paid_at = ?, version = version + 1
                    WHERE card_bill_id = ?
                    """, LocalDateTime.now(ZoneOffset.UTC), cardBillId);
            jdbc.update("UPDATE bill_item SET status = 'PAID', version = version + 1 WHERE card_bill_id = ? AND status = 'BILLED'",
                    cardBillId);
            return settler.onWithdrawalSucceeded(cardBillId, account, withdrawnOn);
        });
    }

    /** 결제일 출금 실패(잔액 부족) 확정 저장(S15P31E103-207 자리). 청구서를 미납으로 적고 같은 트랜잭션에서 취소 회차 정리를 부른다. */
    private List<Refund> withdrawalFailed(long cardBillId) {
        return transaction.execute(status -> {
            jdbc.queryForObject("SELECT card_bill_id FROM card_bill WHERE card_bill_id = ? FOR UPDATE", Long.class, cardBillId);
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            jdbc.update("UPDATE card_bill SET status = 'UNPAID', failed_at = ?, version = version + 1 WHERE card_bill_id = ?",
                    now, cardBillId);
            return settler.onWithdrawalFailed(cardBillId, now);
        });
    }

    private LocalDateTime failedAt(long cardBillId) {
        return jdbc.queryForObject("SELECT failed_at FROM card_bill WHERE card_bill_id = ?", LocalDateTime.class, cardBillId);
    }

    private Map<String, Object> billRow(long cardBillId) {
        return jdbc.queryForMap("""
                SELECT status, confirmed_amount, paid_amount, canceled_unpaid_principal FROM card_bill WHERE card_bill_id = ?
                """, cardBillId);
    }

    /** 청구서에 배정된 무이자 회차(청구액 = 원금). */
    private long billItem(long paymentId, long cardBillId, int seq, String status, long amount) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDate billingMonth = jdbc.queryForObject("SELECT billing_month FROM card_bill WHERE card_bill_id = ?",
                LocalDate.class, cardBillId);
        Map<String, Object> row = new HashMap<>();
        row.put("payment_transaction_id", paymentId);
        row.put("user_id", user);
        row.put("user_card_id", creditCard);
        row.put("card_bill_id", cardBillId);
        row.put("installment_no", seq);
        row.put("billing_month", billingMonth);
        row.put("principal_amount", amount);
        row.put("fee_amount", 0L);
        row.put("billed_amount", amount);
        row.put("status", status);
        row.put("created_at", now);
        row.put("updated_at", now);
        return insert("bill_item", "bill_item_id", row);
    }

    private String itemStatus(long billItemId) {
        return jdbc.queryForObject("SELECT status FROM bill_item WHERE bill_item_id = ?", String.class, billItemId);
    }

    private long itemVersion(long billItemId) {
        return jdbc.queryForObject("SELECT version FROM bill_item WHERE bill_item_id = ?", Long.class, billItemId);
    }

    private long merchant() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return insert("merchant", "merchant_id", Map.of("merchant_name", "테스트 카페", "brand_code", "TEST_CAFE",
                "merchant_category_code", "COFFEE_SHOP", "created_at", now, "updated_at", now));
    }

    private long insert(String table, String idColumn, Map<String, Object> row) {
        return new SimpleJdbcInsert(jdbc).withTableName(table).usingGeneratedKeyColumns(idColumn)
                .usingColumns(row.keySet().toArray(String[]::new))
                .executeAndReturnKey(row).longValue();
    }
}
