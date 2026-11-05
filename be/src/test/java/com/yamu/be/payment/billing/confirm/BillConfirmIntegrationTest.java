package com.yamu.be.payment.billing.confirm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.yamu.be.global.job.JobLocks;
import com.yamu.be.global.job.JobRunCommand;
import com.yamu.be.global.job.JobRunReport;
import com.yamu.be.payment.bank.SsafyTransferApi;
import com.yamu.be.support.Concurrently;
import com.yamu.be.support.MySqlTestSupport;
import com.yamu.be.support.OwnershipFixtures;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

    @Test
    void 청구할인이_있으면_그_청구서만_확정하지_않고_실패로_남기고_다른_청구서는_계속한다() {
        long discounted = accumulating(user, card, NOVEMBER, LocalDate.of(2026, 11, 1));
        long pending = item(payment(user, card, 30_000), user, card, discounted, 1, 30_000, "PENDING");
        long otherCard = creditCard(user, account);
        long other = accumulating(user, otherCard, NOVEMBER, LocalDate.of(2026, 11, 1));
        item(payment(user, otherCard, 9_000), user, otherCard, other, 1, 9_000, "PENDING");
        doReturn(3_000L).when(discounts).discount(org.mockito.ArgumentMatchers.argThat(b -> b.getId() == discounted),
                any());

        JobRunReport report = run(AS_OF, null, false);

        assertThat(report.count(JobRunReport.Outcome.FAILED)).isEqualTo(1);
        assertThat(report.count(JobRunReport.Outcome.SUCCEEDED)).isEqualTo(1);
        assertThat(billRow(discounted).get("status")).isEqualTo("ACCUMULATING");
        assertThat(billRow(discounted).get("confirmed_amount")).isNull();
        assertThat(itemStatus(pending)).isEqualTo("PENDING");
        assertThat(billRow(other).get("status")).isEqualTo("CONFIRMED");
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

    // ───────── 도우미 ─────────

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
