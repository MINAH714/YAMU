package com.yamu.be.payment.billing.confirm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.yamu.be.global.job.JobLocks;
import com.yamu.be.global.job.JobRunCommand;
import com.yamu.be.global.job.JobRunReport;
import com.yamu.be.payment.bank.SsafyTransferApi;
import com.yamu.be.support.AuthUsers;
import com.yamu.be.support.Concurrently;
import com.yamu.be.support.MySqlTestSupport;
import com.yamu.be.support.OwnershipFixtures;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;

/**
 * 청구 확정(Task 5.4.2)을 실제 MySQL·Redis·영업일 달력(내장 2026 공휴일)으로 확인한다. 금융망은 쓰지 않는다.
 *
 * <p>기준: 결제일 15일·마감 14일 카드의 2026-11 청구서(이용기간 10/2~11/1, 원래 결제일 11/15 일요일 → 출금 11/16 월요일).
 * 기준 시각은 2026-11-02 00:30(한국)이다. 승인 때 저장한 출금 예정일은 일부러 11/15로 넣어 확정이 영업일로 다시 계산하는지 본다.
 */
@AutoConfigureMockMvc
@RecordApplicationEvents
@TestPropertySource(properties = "spring.application.name=yamu-bill-confirm-test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BillConfirmIntegrationTest extends MySqlTestSupport {

    static final String ADMIN_KEY = "test-admin-key";
    static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
    static final YearMonth NOVEMBER = YearMonth.of(2026, 11);
    /** 2026-11-02 00:30 KST */
    static final Instant AS_OF = Instant.parse("2026-11-01T15:30:00Z");
    static final LocalDate STORED_WITHDRAWAL = LocalDate.of(2026, 11, 15);
    static final LocalDate BUSINESS_WITHDRAWAL = LocalDate.of(2026, 11, 16);

    @Container
    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @MockitoBean SsafyTransferApi transfer;
    @MockitoSpyBean BillingDiscountSource discounts;

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired BillConfirmJob job;
    @Autowired BillConfirmer confirmer;
    @Autowired ApplicationEvents events;

    OwnershipFixtures fixtures;
    long user;
    long account;
    long card;
    long merchant;

    @BeforeEach
    void setUp() {
        deleteDiscountRows();
        jdbc.update("DELETE FROM refund");
        jdbc.update("DELETE FROM bill_item");
        jdbc.update("DELETE FROM card_bill");
        jdbc.update("DELETE FROM payment_transaction");
        jdbc.update("DELETE FROM user_card");
        jdbc.update("DELETE FROM linked_account");
        jdbc.update("DELETE FROM card_product");
        jdbc.update("DELETE FROM merchant");
        // 승인·취소·청구 사건이 쌓은 알림(Task 9.3.2)이 사용자를 참조한다
        jdbc.update("DELETE FROM notification");
        jdbc.update("DELETE FROM app_user");
        redis.getRequiredConnectionFactory().getConnection().serverCommands().flushAll();
        fixtures = new OwnershipFixtures(jdbc);
        user = fixtures.user();
        account = fixtures.account(user);
        card = creditCard(user, account);
        merchant = insert("merchant", "merchant_id", Map.of("merchant_name", "테스트 카페",
                "brand_code", "CONFIRM_" + UUID.randomUUID(), "merchant_category_code", "COFFEE_SHOP"));
    }

    // ───────── 확정 ─────────

    @Test
    void 마감이_지난_청구서를_청구_전_회차_합계로_확정하고_출금일을_영업일로_다시_정한다() throws Exception {
        long bill = accumulating(user, card, NOVEMBER, LocalDate.of(2026, 11, 1));
        long first = item(payment(user, card, 30_000), user, card, bill, 1, 30_000, "PENDING");
        long second = item(payment(user, card, 12_000), user, card, bill, 1, 12_000, "PENDING");
        long excluded = item(payment(user, card, 8_000), user, card, bill, 1, 8_000, "EXCLUDED");

        mockMvc.perform(post("/api/admin/jobs/bill-confirm/run").header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"asOf\":\"" + AS_OF + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.jobName").value("bill-confirm"))
                .andExpect(jsonPath("$.data.asOf").value("2026-11-01T15:30:00Z"))
                .andExpect(jsonPath("$.data.summary.targets").value(1))
                .andExpect(jsonPath("$.data.summary.succeeded").value(1))
                .andExpect(jsonPath("$.data.results[0].refType").value("CARD_BILL"))
                .andExpect(jsonPath("$.data.results[0].refId").value(bill))
                .andExpect(jsonPath("$.data.results[0].result").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.results[0].amount").value(42_000));

        Map<String, Object> row = billRow(bill);
        assertThat(row.get("status")).isEqualTo("CONFIRMED");
        assertThat(((Number) row.get("confirmed_amount")).longValue()).isEqualTo(42_000);
        assertThat(((Number) row.get("linked_account_id")).longValue()).isEqualTo(account);
        assertThat(row.get("confirmed_at")).isNotNull();
        assertThat(((java.sql.Date) row.get("withdrawal_date")).toLocalDate()).isEqualTo(BUSINESS_WITHDRAWAL);
        assertThat(itemStatus(first)).isEqualTo("BILLED");
        assertThat(itemStatus(second)).isEqualTo("BILLED");
        assertThat(itemStatus(excluded)).isEqualTo("EXCLUDED");
        assertThat(events.stream(BillConfirmedEvent.class)).singleElement().satisfies(event -> {
            assertThat(event.cardBillId()).isEqualTo(bill);
            assertThat(event.confirmedAmount()).isEqualTo(42_000);
            assertThat(event.withdrawalDate()).isEqualTo(BUSINESS_WITHDRAWAL);
            assertThat(event.settledWithoutWithdrawal()).isFalse();
        });
    }

    @Test
    void 마감일_당일이나_이용기간_중인_청구서는_확정하지_않는다() {
        long today = accumulating(user, card, NOVEMBER, LocalDate.of(2026, 11, 2));
        item(payment(user, card, 10_000), user, card, today, 1, 10_000, "PENDING");

        JobRunReport report = run(AS_OF, null, false);

        assertThat(report.targets()).isZero();
        assertThat(billRow(today).get("status")).isEqualTo("ACCUMULATING");
    }

    @Test
    void 서버가_멈췄던_날의_청구서도_다음_실행에_따라잡아_확정한다() {
        long october = accumulating(user, card, YearMonth.of(2026, 10), LocalDate.of(2026, 10, 1));
        item(payment(user, card, 5_000), user, card, october, 1, 5_000, "PENDING");
        long november = accumulating(user, card, NOVEMBER, LocalDate.of(2026, 11, 1));
        item(payment(user, card, 7_000), user, card, november, 1, 7_000, "PENDING");

        JobRunReport report = run(AS_OF, null, false);

        assertThat(report.count(JobRunReport.Outcome.SUCCEEDED)).isEqualTo(2);
        assertThat(billRow(october).get("status")).isEqualTo("CONFIRMED");
        assertThat(billRow(november).get("status")).isEqualTo("CONFIRMED");
    }

    @Test
    void 다시_돌려도_이미_확정된_청구서는_대상이_아니고_확정액이_바뀌지_않는다() {
        long bill = accumulating(user, card, NOVEMBER, LocalDate.of(2026, 11, 1));
        item(payment(user, card, 30_000), user, card, bill, 1, 30_000, "PENDING");
        run(AS_OF, null, false);
        Object confirmedAt = billRow(bill).get("confirmed_at");

        JobRunReport again = run(AS_OF.plusSeconds(86_400), null, false);

        assertThat(again.targets()).isZero();
        assertThat(((Number) billRow(bill).get("confirmed_amount")).longValue()).isEqualTo(30_000);
        assertThat(billRow(bill).get("confirmed_at")).isEqualTo(confirmedAt);
        assertThat(events.stream(BillConfirmedEvent.class)).hasSize(1);
    }

    @Test
    void 같은_청구서를_동시에_확정해도_한_번만_확정된다() throws Exception {
        long bill = accumulating(user, card, NOVEMBER, LocalDate.of(2026, 11, 1));
        item(payment(user, card, 30_000), user, card, bill, 1, 30_000, "PENDING");
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        List<BillConfirmer.Result> results = Concurrently.run(3, () -> confirmer.confirm(bill, now));

        assertThat(results).extracting(BillConfirmer.Result::kind)
                .containsExactlyInAnyOrder(BillConfirmer.Kind.CONFIRMED, BillConfirmer.Kind.ALREADY_DONE,
                        BillConfirmer.Kind.ALREADY_DONE);
        assertThat(((Number) billRow(bill).get("confirmed_amount")).longValue()).isEqualTo(30_000);
        assertThat(events.stream(BillConfirmedEvent.class)).hasSize(1);
    }

    @Test
    void 카드의_출금_계좌가_끊겼으면_확정하지_않고_다음_실행에_다시_본다() {
        long bill = accumulating(user, card, NOVEMBER, LocalDate.of(2026, 11, 1));
        long pending = item(payment(user, card, 30_000), user, card, bill, 1, 30_000, "PENDING");
        jdbc.update("UPDATE linked_account SET status = 'DISCONNECTED' WHERE linked_account_id = ?", account);

        JobRunReport report = run(AS_OF, null, false);

        assertThat(report.results()).singleElement().satisfies(item -> {
            assertThat(item.outcome()).isEqualTo(JobRunReport.Outcome.SKIPPED);
            assertThat(item.refId()).isEqualTo(bill);
        });
        assertThat(billRow(bill).get("status")).isEqualTo("ACCUMULATING");
        assertThat(itemStatus(pending)).isEqualTo("PENDING");

        jdbc.update("UPDATE linked_account SET status = 'CONNECTED' WHERE linked_account_id = ?", account);
        run(AS_OF, null, false);
        assertThat(billRow(bill).get("status")).isEqualTo("CONFIRMED");
    }

    @Test
    void 회차가_모두_빠진_청구서는_0원으로_확정하고_출금_없이_닫는다() {
        long bill = accumulating(user, card, NOVEMBER, LocalDate.of(2026, 11, 1));
        item(payment(user, card, 8_000), user, card, bill, 1, 8_000, "EXCLUDED");
        long empty = accumulating(user, card, YearMonth.of(2026, 10), LocalDate.of(2026, 10, 1));

        run(AS_OF, null, false);

        for (long id : List.of(bill, empty)) {
            Map<String, Object> row = billRow(id);
            assertThat(row.get("status")).isEqualTo("PAID");
            assertThat(((Number) row.get("confirmed_amount")).longValue()).isZero();
            assertThat(((Number) row.get("paid_amount")).longValue()).isZero();
            assertThat(row.get("paid_at")).isNotNull();
            assertThat(row.get("fin_request_no")).isNull();
        }
        assertThat(events.stream(BillConfirmedEvent.class)).allMatch(BillConfirmedEvent::settledWithoutWithdrawal);
    }

    // ───────── 청구할인(Task 5.4.7) ─────────

    @Test
    void 청구할인을_회차_원금_비율로_나눠_저장하고_확정액에서_뺀다() {
        long bill = accumulating(user, card, NOVEMBER, LocalDate.of(2026, 11, 1));
        long lump = payment(user, card, 30_000);
        long lumpItem = item(lump, user, card, bill, 1, 30_000, "PENDING");
        // 90,000원 3개월 할부(30,000×3): 1회차만 이 청구서, 나머지는 다음 달 청구서에 붙을 회차
        long installment = payment(user, card, 90_000);
        long firstItem = item(installment, user, card, bill, 1, 30_000, "PENDING");
        laterItem(installment, 2, 30_000);
        laterItem(installment, 3, 30_000);
        long lumpDiscount = billingDiscount(lump, 3_000, "AT_BILLING", "DISCOUNT");
        long installmentDiscount = billingDiscount(installment, 9_000, "AT_BILLING", "DISCOUNT");
        // 즉시할인·적립은 청구할인이 아니다
        billingDiscount(lump, 1_000, "AT_APPROVAL", "DISCOUNT");
        billingDiscount(installment, 500, "POINT_ACCRUAL", "POINT");

        JobRunReport report = run(AS_OF, null, false);

        assertThat(report.count(JobRunReport.Outcome.SUCCEEDED)).isEqualTo(1);
        assertThat(billRow(bill).get("status")).isEqualTo("CONFIRMED");
        assertThat(billRow(bill).get("confirmed_amount")).isEqualTo(54_000L);
        assertThat(jdbc.queryForList("SELECT bill_item_id, payment_benefit_id, amount FROM bill_item_discount "
                + "WHERE card_bill_id = ? ORDER BY bill_item_id", bill))
                .containsExactly(
                        Map.of("bill_item_id", lumpItem, "payment_benefit_id", lumpDiscount, "amount", 3_000L),
                        Map.of("bill_item_id", firstItem, "payment_benefit_id", installmentDiscount, "amount", 3_000L));
    }

    @Test
    void 할부_할인의_원_단위_나머지는_마지막_회차에_간다() {
        long bill = accumulating(user, card, NOVEMBER, LocalDate.of(2026, 11, 1));
        // 100,000원 3개월(33,333/33,333/33,334), 할인 1,000원 → 333 / 333 / 334. 마지막 회차가 이 청구서
        long installment = payment(user, card, 100_000);
        laterItem(installment, 1, 33_333);
        laterItem(installment, 2, 33_333);
        long last = item(installment, user, card, bill, 3, 33_334, "PENDING");
        billingDiscount(installment, 1_000, "AT_BILLING", "DISCOUNT");

        run(AS_OF, null, false);

        assertThat(jdbc.queryForObject("SELECT amount FROM bill_item_discount WHERE bill_item_id = ?", Long.class, last))
                .isEqualTo(334L);
        assertThat(billRow(bill).get("confirmed_amount")).isEqualTo(33_000L);
    }

    @Test
    void 나머지가_마지막_회차_원금을_넘으면_앞_회차로_넘겨_1원도_잃지_않는다() {
        // 원금 3,333 / 3,333 / 3,334에 할인 9,999원: 예전 규칙은 마지막이 3,335원이라 1원이 잘렸다(!141 리뷰)
        long bill = accumulating(user, card, NOVEMBER, LocalDate.of(2026, 11, 1));
        long installment = payment(user, card, 10_000);
        long first = item(installment, user, card, bill, 1, 3_333, "PENDING");
        long second = item(installment, user, card, bill, 2, 3_333, "PENDING");
        long third = item(installment, user, card, bill, 3, 3_334, "PENDING");
        billingDiscount(installment, 9_999, "AT_BILLING", "DISCOUNT");

        run(AS_OF, null, false);

        assertThat(jdbc.queryForList("SELECT bill_item_id, amount FROM bill_item_discount WHERE card_bill_id = ? "
                + "ORDER BY bill_item_id", bill)).containsExactly(
                Map.of("bill_item_id", first, "amount", 3_332L),
                Map.of("bill_item_id", second, "amount", 3_333L),
                Map.of("bill_item_id", third, "amount", 3_334L));
        assertThat(billRow(bill).get("confirmed_amount")).isEqualTo(1L);
    }

    @Test
    void 회차_하나에_나눈_할인이_그_회차_청구액보다_크면_확정하지_않는다() {
        long bill = accumulating(user, card, NOVEMBER, LocalDate.of(2026, 11, 1));
        long payment = payment(user, card, 30_000);
        long small = item(payment, user, card, bill, 1, 10_000, "PENDING");
        long large = item(payment(user, card, 20_000), user, card, bill, 1, 20_000, "PENDING");
        long benefit = billingDiscount(payment, 1_000, "AT_BILLING", "DISCOUNT");
        // 합계(12,000)는 회차 합(30,000) 안이지만 회차 하나(10,000)를 넘는 깨진 배분
        doReturn(List.of(new BillingDiscountSource.Allocation(small, payment, benefit, "깨진 배분", 12_000)))
                .when(discounts).allocate(bill);

        JobRunReport report = run(AS_OF, null, false);

        assertThat(report.count(JobRunReport.Outcome.FAILED)).isEqualTo(1);
        assertThat(billRow(bill).get("status")).isEqualTo("ACCUMULATING");
        assertThat(itemStatus(small)).isEqualTo("PENDING");
        assertThat(itemStatus(large)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bill_item_discount WHERE card_bill_id = ?", Integer.class,
                bill)).isZero();
    }

    @Test
    void 다시_실행해도_할인_배분을_한_번만_저장한다() {
        long bill = accumulating(user, card, NOVEMBER, LocalDate.of(2026, 11, 1));
        long lump = payment(user, card, 30_000);
        item(lump, user, card, bill, 1, 30_000, "PENDING");
        billingDiscount(lump, 3_000, "AT_BILLING", "DISCOUNT");

        run(AS_OF, null, false);
        JobRunReport again = run(AS_OF, null, false);

        assertThat(again.count(JobRunReport.Outcome.SUCCEEDED)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bill_item_discount WHERE card_bill_id = ?", Integer.class,
                bill)).isEqualTo(1);
        assertThat(billRow(bill).get("confirmed_amount")).isEqualTo(27_000L);
    }

    @Test
    void 할인_계산이_실패한_청구서만_실패로_남기고_다른_청구서는_계속한다() {
        long failing = accumulating(user, card, NOVEMBER, LocalDate.of(2026, 11, 1));
        long pending = item(payment(user, card, 30_000), user, card, failing, 1, 30_000, "PENDING");
        long otherCard = creditCard(user, account);
        long other = accumulating(user, otherCard, NOVEMBER, LocalDate.of(2026, 11, 1));
        item(payment(user, otherCard, 9_000), user, otherCard, other, 1, 9_000, "PENDING");
        doThrow(new IllegalStateException("할인 원천 오류")).when(discounts).allocate(failing);

        JobRunReport report = run(AS_OF, null, false);

        assertThat(report.count(JobRunReport.Outcome.FAILED)).isEqualTo(1);
        assertThat(report.count(JobRunReport.Outcome.SUCCEEDED)).isEqualTo(1);
        assertThat(billRow(failing).get("status")).isEqualTo("ACCUMULATING");
        assertThat(billRow(failing).get("confirmed_amount")).isNull();
        assertThat(itemStatus(pending)).isEqualTo("PENDING");
        assertThat(billRow(other).get("status")).isEqualTo("CONFIRMED");
    }

    @Test
    void 확정_뒤_취소하면_회차_청구액에서_배분_할인을_뺀_금액을_환급한다() throws Exception {
        long bill = accumulating(user, card, NOVEMBER, LocalDate.of(2026, 11, 1));
        long lump = payment(user, card, 30_000);
        long lumpItem = item(lump, user, card, bill, 1, 30_000, "PENDING");
        billingDiscount(lump, 3_000, "AT_BILLING", "DISCOUNT");
        run(AS_OF, null, false);

        mockMvc.perform(post("/api/payments/{paymentId}/cancel", lump).with(AuthUsers.asUser(user))
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.refunds[0].amount").value(27_000));

        Map<String, Object> refund = jdbc.queryForMap("SELECT principal_amount, refund_amount FROM refund "
                + "WHERE bill_item_id = ?", lumpItem);
        assertThat(refund.get("principal_amount")).isEqualTo(27_000L);
        assertThat(refund.get("refund_amount")).isEqualTo(27_000L);
    }

    @Test
    void 청구서_조회는_확정_할인과_진행_중_예상_할인을_보여_준다() throws Exception {
        long bill = accumulating(user, card, NOVEMBER, LocalDate.of(2026, 11, 1));
        long lump = payment(user, card, 30_000);
        item(lump, user, card, bill, 1, 30_000, "PENDING");
        long discount = billingDiscount(lump, 3_000, "AT_BILLING", "DISCOUNT");

        // 확정 전: 예상 청구할인과 예상 청구액. discounts[]도 같은 금액을 확정 시각 없이 보인다(!141 리뷰)
        mockMvc.perform(get("/api/billings/{billId}", bill).with(AuthUsers.asUser(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.amounts.billingDiscount").value(3_000))
                .andExpect(jsonPath("$.data.amounts.expectedAmount").value(27_000))
                .andExpect(jsonPath("$.data.discounts.length()").value(1))
                .andExpect(jsonPath("$.data.discounts[0].paymentBenefitId").value(discount))
                .andExpect(jsonPath("$.data.discounts[0].amount").value(3_000))
                .andExpect(jsonPath("$.data.discounts[0].confirmedAt").doesNotExist());

        run(AS_OF, null, false);

        mockMvc.perform(get("/api/billings/{billId}", bill).with(AuthUsers.asUser(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.amounts.itemAmount").value(30_000))
                .andExpect(jsonPath("$.data.amounts.billingDiscount").value(3_000))
                .andExpect(jsonPath("$.data.amounts.finalAmount").value(27_000))
                .andExpect(jsonPath("$.data.discounts[0].paymentBenefitId").value(discount))
                .andExpect(jsonPath("$.data.discounts[0].paymentId").value(lump))
                .andExpect(jsonPath("$.data.discounts[0].amount").value(3_000))
                .andExpect(jsonPath("$.data.discounts[0].confirmedAt").isString());
        mockMvc.perform(get("/api/billings").with(AuthUsers.asUser(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].billingDiscount").value(3_000))
                .andExpect(jsonPath("$.data.items[0].finalAmount").value(27_000));
    }

    @Test
    void dryRun은_대상만_세고_바꾸지_않는다() {
        long bill = accumulating(user, card, NOVEMBER, LocalDate.of(2026, 11, 1));
        item(payment(user, card, 30_000), user, card, bill, 1, 30_000, "PENDING");

        JobRunReport report = run(AS_OF, null, true);

        assertThat(report.results()).singleElement()
                .satisfies(item -> assertThat(item.result()).isEqualTo("DRY_RUN"));
        assertThat(billRow(bill).get("status")).isEqualTo("ACCUMULATING");
        assertThat(events.stream(BillConfirmedEvent.class)).isEmpty();
    }

    @Test
    void userId를_주면_그_사용자의_청구서만_확정한다() {
        long mine = accumulating(user, card, NOVEMBER, LocalDate.of(2026, 11, 1));
        long otherUser = fixtures.user();
        long otherAccount = fixtures.account(otherUser);
        long otherCard = creditCard(otherUser, otherAccount);
        long others = accumulating(otherUser, otherCard, NOVEMBER, LocalDate.of(2026, 11, 1));

        run(AS_OF, user, false);

        assertThat(billRow(mine).get("status")).isNotEqualTo("ACCUMULATING");
        assertThat(billRow(others).get("status")).isEqualTo("ACCUMULATING");
    }

    // ───────── 운영 API ─────────

    @Test
    void 운영_키가_없거나_다르면_403_ADMIN_ONLY() throws Exception {
        mockMvc.perform(post("/api/admin/jobs/bill-confirm/run"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ADMIN_ONLY"));
        mockMvc.perform(post("/api/admin/jobs/bill-confirm/run").header("X-Admin-Key", "wrong"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ADMIN_ONLY"));
    }

    @Test
    void 없는_작업은_404_JOB_NOT_FOUND() throws Exception {
        mockMvc.perform(post("/api/admin/jobs/no-such-job/run").header("X-Admin-Key", ADMIN_KEY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("JOB_NOT_FOUND"));
    }

    @Test
    void 같은_작업이_돌고_있으면_409_JOB_ALREADY_RUNNING() throws Exception {
        redis.opsForValue().set(JobLocks.lockKey(BillConfirmJob.NAME), "other-server");

        mockMvc.perform(post("/api/admin/jobs/bill-confirm/run").header("X-Admin-Key", ADMIN_KEY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("JOB_ALREADY_RUNNING"));
    }

    @Test
    void 본문_없이_부르면_지금_기준으로_돈다() throws Exception {
        mockMvc.perform(post("/api/admin/jobs/bill-confirm/run").header("X-Admin-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dryRun").value(false))
                .andExpect(jsonPath("$.data.summary.targets").value(0));
    }

    // ───────── 미래 asOf (수동 실행은 오늘 한국 날짜까지만) ─────────

    @Test
    void 수동_실행은_미래_asOf면_userId를_줘도_400이고_아무것도_바꾸지_않는다() throws Exception {
        // 실제 시계 기준: 내일 마감인 청구서. 사흘 뒤로 돌리면 확정 대상이 되지만 미래라 막는다
        LocalDate today = LocalDate.now(KOREA);
        long bill = accumulating(user, card, YearMonth.from(today.plusDays(1)), today.plusDays(1));
        item(payment(user, card, 10_000), user, card, bill, 1, 10_000, "PENDING");

        mockMvc.perform(post("/api/admin/jobs/bill-confirm/run").header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"asOf\":\"" + koreanTime0030(today.plusDays(3)) + "\",\"userId\":" + user + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.details.fieldErrors[0].field").value("asOf"));

        assertThat(billRow(bill).get("status")).isEqualTo("ACCUMULATING");
    }

    @Test
    void 수동_실행은_미래_asOf면_dryRun도_400이다() throws Exception {
        LocalDate tomorrow = LocalDate.now(KOREA).plusDays(1);

        mockMvc.perform(post("/api/admin/jobs/bill-confirm/run").header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"asOf\":\"" + koreanTime0030(tomorrow) + "\",\"dryRun\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.fieldErrors[0].field").value("asOf"));
    }

    @Test
    void 수동_실행은_오늘_안의_뒤_시각은_받는다() throws Exception {
        // 대상을 한국 날짜로 고르므로 오늘 23:59는 지금과 결과가 같다
        Instant lateToday = LocalDate.now(KOREA).atTime(23, 59).atZone(KOREA).toInstant();

        mockMvc.perform(post("/api/admin/jobs/bill-confirm/run").header("X-Admin-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"asOf\":\"" + lateToday + "\"}"))
                .andExpect(status().isOk());
    }

    // ───────── 도우미 ─────────

    /** 할인 배분·혜택 행은 회차·결제·상품을 지울 수 없게 하므로 앞뒤로 지운다(다음 테스트 클래스도 같은 DB를 쓴다). */
    @AfterEach
    void deleteDiscountRows() {
        jdbc.update("DELETE FROM bill_item_discount");
        jdbc.update("DELETE FROM payment_benefit");
        jdbc.update("DELETE FROM card_benefit");
    }

    /** 결제의 혜택 한 줄(승인 원장이 적는 payment_benefit)과 그 혜택 정책(card_benefit). 원화 금액으로 적는다. */
    private long billingDiscount(long payment, long amount, String timing, String type) {
        Long product = jdbc.queryForObject("SELECT card_product_id FROM payment_transaction WHERE payment_transaction_id = ?",
                Long.class, payment);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Map<String, Object> benefit = new HashMap<>(Map.of("card_product_id", product,
                "benefit_code", "B_" + UUID.randomUUID().toString().substring(0, 8), "policy_version", 1,
                "benefit_name", "카페 청구할인", "benefit_type", type, "calculation_type", "FIXED",
                "fixed_value", java.math.BigDecimal.valueOf(amount), "value_unit", "POINT".equals(type) ? "POINT" : "KRW",
                "application_timing", timing, "created_at", now));
        long benefitId = new SimpleJdbcInsert(jdbc).withTableName("card_benefit").usingGeneratedKeyColumns("card_benefit_id")
                .usingColumns(benefit.keySet().toArray(String[]::new)).executeAndReturnKey(benefit).longValue();
        Map<String, Object> applied = new HashMap<>(Map.of("payment_transaction_id", payment, "card_product_id", product,
                "card_benefit_id", benefitId, "benefit_value", java.math.BigDecimal.valueOf(amount),
                "value_unit", "POINT".equals(type) ? "POINT" : "KRW", "krw_amount", amount, "created_at", now));
        return new SimpleJdbcInsert(jdbc).withTableName("payment_benefit").usingGeneratedKeyColumns("payment_benefit_id")
                .usingColumns(applied.keySet().toArray(String[]::new)).executeAndReturnKey(applied).longValue();
    }

    /** 그 한국 날짜 00:30을 UTC 시각으로 */
    private static Instant koreanTime0030(LocalDate date) {
        return date.atTime(0, 30).atZone(KOREA).toInstant();
    }

    private JobRunReport run(Instant asOf, Long userId, boolean dryRun) {
        return job.run(new JobRunCommand(asOf, userId, dryRun));
    }

    /** 결제일 15일·마감 14일 신용카드 */
    private long creditCard(long owner, long linkedAccount) {
        long product = fixtures.product();
        long id = fixtures.card(owner, product, linkedAccount, "ACTIVE");
        jdbc.update("UPDATE user_card SET payment_day = 15 WHERE user_card_id = ?", id);
        return id;
    }

    private long accumulating(long owner, long userCard, YearMonth month, LocalDate periodEnd) {
        return insert("card_bill", "card_bill_id", Map.of("user_id", owner, "user_card_id", userCard,
                "billing_month", month.atDay(1), "period_start", periodEnd.minusMonths(1).plusDays(1),
                "period_end", periodEnd, "withdrawal_date", periodEnd.plusDays(14), "status", "ACCUMULATING"));
    }

    private long payment(long owner, long userCard, long amount) {
        long product = jdbc.queryForObject("SELECT card_product_id FROM user_card WHERE user_card_id = ?", Long.class,
                userCard);
        LocalDateTime approvedAt = LocalDateTime.of(2026, 10, 20, 3, 0);
        return insert("payment_transaction", "payment_transaction_id", Map.ofEntries(Map.entry("user_id", owner),
                Map.entry("merchant_id", merchant), Map.entry("user_card_id", userCard),
                Map.entry("card_product_id", product), Map.entry("request_key", UUID.randomUUID().toString()),
                Map.entry("request_fingerprint", randomHash()), Map.entry("source", "YAMU"),
                Map.entry("status", "APPROVED"), Map.entry("requested_amount", amount),
                Map.entry("approved_amount", amount), Map.entry("installment_months", 1),
                Map.entry("recognized_performance_amount", 0L),
                Map.entry("performance_month", LocalDate.of(2026, 10, 1)),
                Map.entry("requested_at", approvedAt), Map.entry("approved_at", approvedAt)));
    }

    private long item(long payment, long owner, long userCard, long bill, int no, long principal, String status) {
        return insert("bill_item", "bill_item_id", Map.of("payment_transaction_id", payment, "user_id", owner,
                "user_card_id", userCard, "card_bill_id", bill, "installment_no", no,
                "billing_month", NOVEMBER.atDay(1), "principal_amount", principal, "fee_amount", 0L,
                "billed_amount", principal, "status", status));
    }

    /** 아직 청구서에 붙지 않은 다른 달 회차(할부의 다른 회차). 할인 배분의 원금 비율에만 쓰인다. */
    private void laterItem(long payment, int no, long principal) {
        insert("bill_item", "bill_item_id", Map.of("payment_transaction_id", payment, "user_id", user,
                "user_card_id", card, "installment_no", no, "billing_month", NOVEMBER.plusMonths(no).atDay(1),
                "principal_amount", principal, "fee_amount", 0L, "billed_amount", principal, "status", "PENDING"));
    }

    private Map<String, Object> billRow(long id) {
        return jdbc.queryForMap("SELECT * FROM card_bill WHERE card_bill_id = ?", id);
    }

    private String itemStatus(long id) {
        return jdbc.queryForObject("SELECT status FROM bill_item WHERE bill_item_id = ?", String.class, id);
    }

    private long insert(String table, String idColumn, Map<String, Object> fields) {
        Map<String, Object> row = new HashMap<>(fields);
        row.putIfAbsent("created_at", LocalDateTime.now(ZoneOffset.UTC));
        row.putIfAbsent("updated_at", LocalDateTime.now(ZoneOffset.UTC));
        return new SimpleJdbcInsert(jdbc).withTableName(table).usingGeneratedKeyColumns(idColumn)
                .usingColumns(row.keySet().toArray(String[]::new)).executeAndReturnKey(row).longValue();
    }

    private static byte[] randomHash() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        return ByteBuffer.allocate(32).putLong(a.getMostSignificantBits()).putLong(a.getLeastSignificantBits())
                .putLong(b.getMostSignificantBits()).putLong(b.getLeastSignificantBits()).array();
    }
}
