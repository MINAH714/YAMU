package com.yamu.be.cashflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.yamu.be.support.AuthUsers;
import com.yamu.be.support.MySqlTestSupport;
import com.yamu.be.support.OwnershipFixtures;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 결제 이력의 반복 지출 후보(S15P31E103-349)를 실제 MySQL·가계부 목록 API로 확인한다. 후보 쓰기는 따로 커밋해야 하므로
 * 테스트 트랜잭션(@Transactional)을 쓰지 않고, 테스트마다 새 사용자를 만든다.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.application.name=yamu-recurring-expense-test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RecurringExpenseDetectorTest extends MySqlTestSupport {
    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired RecurringExpenseDetector detector;

    OwnershipFixtures ownership;
    CashflowFixtures cashflows;
    long owner;
    long card;
    long merchant;
    LocalDate today;
    YearMonth month;

    @BeforeEach
    void setUp() {
        ownership = new OwnershipFixtures(jdbc);
        cashflows = new CashflowFixtures(jdbc);
        owner = ownership.user();
        card = ownership.card(owner, ownership.product(), ownership.account(owner), "ACTIVE");
        merchant = cashflows.merchant("TELECOM");
        today = LocalDate.now(KOREA);
        month = YearMonth.from(today);
    }

    @Test
    void 같은_카드_가맹점_금액이_서로_다른_3개_달에_한_번씩이면_후보로_만들고_근거를_붙인다() throws Exception {
        // SEED 결제도 센다(명세 "결제 이력(SEED 포함)").
        paidMonthly(owner, card, merchant, 55_000L, 3, 3, "SEED");
        LocalDate lastPaid = month.minusMonths(1).atDay(10);

        candidates(owner)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidateCount").value(1))
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].type").value("EXPENSE"))
                .andExpect(jsonPath("$.data.items[0].title").value("테스트 가맹점"))
                .andExpect(jsonPath("$.data.items[0].amount").value(55_000))
                .andExpect(jsonPath("$.data.items[0].occurrenceDate")
                        .value(RecurringExpenseDetector.nextOccurrence(lastPaid, today).toString()))
                .andExpect(jsonPath("$.data.items[0].recurrence.frequency").value("MONTHLY"))
                .andExpect(jsonPath("$.data.items[0].recurrence.dayOfMonth").value(10))
                .andExpect(jsonPath("$.data.items[0].merchantCategoryCode").value("TELECOM"))
                .andExpect(jsonPath("$.data.items[0].userCardId").value(card))
                .andExpect(jsonPath("$.data.items[0].accountId").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].source").value("DETECTED"))
                .andExpect(jsonPath("$.data.items[0].status").value("CANDIDATE"))
                .andExpect(jsonPath("$.data.items[0].detection.merchantName").value("테스트 가맹점"))
                .andExpect(jsonPath("$.data.items[0].detection.sampleCount").value(3))
                .andExpect(jsonPath("$.data.items[0].detection.amountRange.min").value(55_000))
                .andExpect(jsonPath("$.data.items[0].detection.amountRange.max").value(55_000));
    }

    @Test
    void 두_개_달만_나오거나_한_달에_두_번_나오면_후보가_아니다() throws Exception {
        long twoMonths = cashflows.merchant("TELECOM");
        long twice = cashflows.merchant("CAFE");
        paidMonthly(owner, card, twoMonths, 10_000L, 2, 2, "YAMU");
        paidMonthly(owner, card, twice, 4_500L, 3, 3, "YAMU");
        cashflows.payment(owner, card, twice, 4_500L, month.minusMonths(1).atDay(20), "YAMU", "APPROVED");

        candidates(owner).andExpect(jsonPath("$.data.items", hasSize(0)))
                .andExpect(jsonPath("$.data.candidateCount").value(0));
    }

    @Test
    void 금액이_다르면_다른_지출이고_취소_결제는_세지_않는다() throws Exception {
        cashflows.payment(owner, card, merchant, 55_000L, month.minusMonths(3).atDay(10), "YAMU", "APPROVED");
        cashflows.payment(owner, card, merchant, 56_000L, month.minusMonths(2).atDay(10), "YAMU", "APPROVED");
        cashflows.payment(owner, card, merchant, 55_000L, month.minusMonths(1).atDay(10), "YAMU", "CANCELED");

        candidates(owner).andExpect(jsonPath("$.data.items", hasSize(0)));
    }

    @Test
    void 여러_번_불러도_후보는_하나다() throws Exception {
        paidMonthly(owner, card, merchant, 55_000L, 3, 3, "YAMU");

        candidates(owner).andExpect(jsonPath("$.data.candidateCount").value(1));
        candidates(owner).andExpect(jsonPath("$.data.candidateCount").value(1));
        // 확인한 항목 목록도 탐지를 부르지만 후보를 또 만들지 않는다.
        mockMvc.perform(get("/api/cashflow-items").with(AuthUsers.asUser(owner)))
                .andExpect(jsonPath("$.data.candidateCount").value(1));

        assertThat(detectedRows(owner)).isEqualTo(1);
    }

    @Test
    void 같은_사용자의_두_요청이_동시에_와도_후보는_하나다() throws Exception {
        paidMonthly(owner, card, merchant, 55_000L, 3, 3, "YAMU");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Map<Long, RecurringExpenseDetector.Detection>>> results = new ArrayList<>();
        try {
            for (int i = 0; i < 2; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return detector.refresh(owner);
                }));
            }
            start.countDown();
            for (Future<Map<Long, RecurringExpenseDetector.Detection>> result : results) {
                // 늦은 쪽은 탐지 키 UNIQUE에 걸린 뒤 다시 맞춰 같은 후보를 돌려준다.
                assertThat(result.get(30, TimeUnit.SECONDS)).hasSize(1);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(detectedRows(owner)).isEqualTo(1);
    }

    @Test
    void 무시한_후보는_다시_만들지_않는다() throws Exception {
        paidMonthly(owner, card, merchant, 55_000L, 3, 3, "YAMU");
        long candidate = onlyDetectedRow(owner);

        mockMvc.perform(patch("/api/cashflow-items/" + candidate).with(AuthUsers.asUser(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{ \"status\": \"IGNORED\" }"))
                .andExpect(status().isOk());

        candidates(owner).andExpect(jsonPath("$.data.items", hasSize(0)))
                .andExpect(jsonPath("$.data.candidateCount").value(0));
        assertThat(detectedRows(owner)).isEqualTo(1);
    }

    @Test
    void 더_이상_반복이_아니면_손대지_않은_확인_전_후보는_지우고_확인한_후보는_남긴다() throws Exception {
        long confirmedMerchant = cashflows.merchant("OTT");
        List<Long> stale = paidMonthly(owner, card, merchant, 55_000L, 3, 3, "YAMU");
        List<Long> kept = paidMonthly(owner, card, confirmedMerchant, 17_000L, 3, 3, "YAMU");
        candidates(owner).andExpect(jsonPath("$.data.candidateCount").value(2));
        long confirmed = jdbc.queryForObject("SELECT cashflow_item_id FROM cashflow_item WHERE user_id = ? "
                + "AND detected_merchant_id = ?", Long.class, owner, confirmedMerchant);
        mockMvc.perform(patch("/api/cashflow-items/" + confirmed).with(AuthUsers.asUser(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{ \"status\": \"CONFIRMED\" }"))
                .andExpect(status().isOk());

        // 두 지출 모두 결제를 취소해 더는 반복으로 잡히지 않게 한다.
        cancel(stale.get(stale.size() - 1));
        cancel(kept.get(kept.size() - 1));
        candidates(owner).andExpect(jsonPath("$.data.items", hasSize(0)));

        assertThat(jdbc.queryForList("SELECT detected_merchant_id FROM cashflow_item WHERE user_id = ?",
                Long.class, owner)).containsExactly(confirmedMerchant);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cashflow_occurrence WHERE user_id = ?", Long.class,
                owner)).isEqualTo(1L);
    }

    @Test
    void 사용자가_회차를_고친_후보는_반복이_끊겨도_남긴다() throws Exception {
        List<Long> payments = paidMonthly(owner, card, merchant, 55_000L, 3, 3, "YAMU");
        long candidate = onlyDetectedRow(owner);
        jdbc.update("UPDATE cashflow_occurrence SET is_overridden = TRUE, amount = 50000 WHERE cashflow_item_id = ?",
                candidate);

        cancel(payments.get(payments.size() - 1));
        candidates(owner);

        assertThat(detectedRows(owner)).isEqualTo(1);
    }

    @Test
    void 남의_반복_결제는_내_후보가_되지_않고_남의_후보도_내_목록에_없다() throws Exception {
        long stranger = ownership.user();
        long strangerCard = ownership.card(stranger, ownership.product(), ownership.account(stranger), "ACTIVE");
        paidMonthly(stranger, strangerCard, merchant, 55_000L, 3, 3, "YAMU");
        candidates(stranger).andExpect(jsonPath("$.data.candidateCount").value(1));

        candidates(owner).andExpect(jsonPath("$.data.items", hasSize(0)))
                .andExpect(jsonPath("$.data.candidateCount").value(0));
        assertThat(detectedRows(owner)).isZero();
    }

    @Test
    void 등록_해제한_카드의_반복_결제는_후보로_만들지_않는다() throws Exception {
        long removed = ownership.card(owner, ownership.product(), ownership.account(owner), "UNREGISTERED");
        paidMonthly(owner, removed, merchant, 55_000L, 3, 3, "YAMU");

        candidates(owner).andExpect(jsonPath("$.data.items", hasSize(0)));
    }

    @Test
    void 다음_예정일은_마지막_결제_다음_달부터_오늘_이후_첫_날이고_없는_날은_말일이다() {
        LocalDate nov9 = LocalDate.of(2026, 11, 9);
        assertThat(RecurringExpenseDetector.nextOccurrence(LocalDate.of(2026, 10, 5), nov9))
                .isEqualTo(LocalDate.of(2026, 12, 5));
        assertThat(RecurringExpenseDetector.nextOccurrence(LocalDate.of(2026, 10, 20), nov9))
                .isEqualTo(LocalDate.of(2026, 11, 20));
        assertThat(RecurringExpenseDetector.nextOccurrence(nov9, nov9)).isEqualTo(LocalDate.of(2026, 12, 9));
        assertThat(RecurringExpenseDetector.nextOccurrence(LocalDate.of(2026, 10, 31), nov9))
                .isEqualTo(LocalDate.of(2026, 11, 30));
        assertThat(RecurringExpenseDetector.nextOccurrence(LocalDate.of(2026, 10, 9), nov9)).isEqualTo(nov9);
    }

    /** 확인 전 후보 목록(기간을 넉넉히 잡고 규칙 하나씩). */
    private ResultActions candidates(long user) throws Exception {
        return mockMvc.perform(get("/api/cashflow-items").with(AuthUsers.asUser(user))
                .param("status", "CANDIDATE").param("from", today.toString())
                .param("to", today.plusDays(62).toString()).param("expand", "false"));
    }

    /**
     * months개 달(이번 달 앞 firstAgo번째 달부터) 10일에 한 번씩 승인된 결제를 넣고 결제 번호를 날짜순으로 돌려준다.
     */
    private List<Long> paidMonthly(long user, long userCard, long merchantId, long amount, int firstAgo, int months,
            String source) {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < months; i++) {
            ids.add(cashflows.payment(user, userCard, merchantId, amount, month.minusMonths(firstAgo - i).atDay(10),
                    source, "APPROVED"));
        }
        return ids;
    }

    private void cancel(long payment) {
        jdbc.update("UPDATE payment_transaction SET status = 'CANCELED', canceled_at = DATE_ADD(approved_at, INTERVAL 1 HOUR) "
                + "WHERE payment_transaction_id = ?", payment);
    }

    private long detectedRows(long user) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM cashflow_item WHERE user_id = ? AND source = 'DETECTED'",
                Long.class, user);
    }

    private long onlyDetectedRow(long user) throws Exception {
        candidates(user).andExpect(jsonPath("$.data.candidateCount").value(1));
        return jdbc.queryForObject("SELECT cashflow_item_id FROM cashflow_item WHERE user_id = ? AND source = 'DETECTED'",
                Long.class, user);
    }
}
