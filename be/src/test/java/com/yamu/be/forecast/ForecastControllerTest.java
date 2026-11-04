package com.yamu.be.forecast;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.yamu.be.account.AccountBalance;
import com.yamu.be.account.AccountBalanceService;
import com.yamu.be.cashflow.CashflowItem;
import com.yamu.be.cashflow.CashflowService;
import com.yamu.be.support.AuthUsers;
import com.yamu.be.support.MySqlTestSupport;
import com.yamu.be.support.OwnershipFixtures;
import java.nio.ByteBuffer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * 잔고예보 조회 API를 인증 필터·실제 MySQL까지 묶어 확인한다. 잔액(BE 1, 금융망)만 가짜로 두고,
 * 임시 청구 원천({@link AccumulatingCardBillReader})이 실제 card_bill·bill_item을 읽는 것을 본다.
 */
@Transactional
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.application.name=yamu-forecast-api-test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ForecastControllerTest extends MySqlTestSupport {
    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean AccountBalanceService balances;
    @Autowired CashflowService cashflows;

    long owner;
    long accountA;
    long accountB;
    long cardOnA;
    long cardOnB;
    long merchant;
    LocalDate today;

    @BeforeEach
    void setUp() {
        OwnershipFixtures fixtures = new OwnershipFixtures(jdbc);
        owner = fixtures.user();
        accountA = fixtures.account(owner);
        accountB = fixtures.account(owner);
        long product = fixtures.product();
        cardOnA = fixtures.card(owner, product, accountA, "ACTIVE");
        cardOnB = fixtures.card(owner, product, accountB, "ACTIVE");
        merchant = merchant();
        today = LocalDate.now(KOREA);
        LocalDateTime fetchedAt = LocalDateTime.now(ZoneOffset.UTC);
        when(balances.getBalances(eq(owner), anyList(), anyBoolean())).thenAnswer(invocation -> {
            List<Long> ids = invocation.getArgument(1);
            return ids.stream().map(id -> new AccountBalance(id, 1_000_000L, fetchedAt)).toList();
        });
    }

    @Test
    void 토큰이_없으면_401이다() throws Exception {
        mockMvc.perform(get("/api/forecasts/balance").param("accountIds", String.valueOf(accountA)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void 계좌를_보내지_않으면_400_ACCOUNT_SELECTION_REQUIRED다() throws Exception {
        mockMvc.perform(get("/api/forecasts/balance").with(AuthUsers.asUser(owner)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_SELECTION_REQUIRED"));
    }

    @Test
    void 기간_값의_형식이_틀리면_400이다() throws Exception {
        mockMvc.perform(get("/api/forecasts/balance").with(AuthUsers.asUser(owner))
                        .param("accountIds", String.valueOf(accountA)).param("until", "WEEK"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void 진행_중_청구서의_청구_전_회차만_카드_출금_계좌에서_출금일에_빠진다() throws Exception {
        // A 카드: 출금일 10일 뒤, 청구 전 30,000원 + 확정 전 취소된 5,000원(빼야 함)
        long billA = bill(cardOnA, today.plusDays(10), 0);
        item(billA, cardOnA, 30_000L, "PENDING");
        item(billA, cardOnA, 5_000L, "EXCLUDED");
        // A 카드의 다음 달 빈 청구서(승인 저장 실패로 남은 것)는 넣지 않는다
        bill(cardOnA, today.plusDays(40), 1);
        // B 카드 청구는 B를 고르지 않았으므로 넣지 않는다
        item(bill(cardOnB, today.plusDays(5), 0), cardOnB, 70_000L, "PENDING");

        mockMvc.perform(get("/api/forecasts/balance").with(AuthUsers.asUser(owner))
                        .param("accountIds", String.valueOf(accountA)).param("until", "DAYS").param("days", "14"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accountIds[0]").value(accountA))
                .andExpect(jsonPath("$.data.currentTotalBalance").value(1_000_000))
                .andExpect(jsonPath("$.data.horizon.days").value(14))
                .andExpect(jsonPath("$.data.alertThreshold").value(100_000))
                .andExpect(jsonPath("$.data.series", hasSize(15)))
                .andExpect(jsonPath("$.data.series[10].expected").value(970_000))
                .andExpect(jsonPath("$.data.series[10].confirmed").value(1_000_000))
                .andExpect(jsonPath("$.data.series[14].expected").value(970_000))
                // 생활비 원천이 아직 자료 없음이라 보수적 선은 비운다
                .andExpect(jsonPath("$.data.series[14].conservative").value(nullValue()))
                .andExpect(jsonPath("$.data.livingCost.dailyAverage").value(nullValue()))
                // 앱은 이 값으로 "생활비 미반영"을 안내한다(요약 상태만으로는 생활비가 빠졌는지 알 수 없음)
                .andExpect(jsonPath("$.data.livingCost.dataOrigin").value("INSUFFICIENT"))
                .andExpect(jsonPath("$.data.livingCost.confidence").value("INSUFFICIENT"))
                .andExpect(jsonPath("$.data.excluded", hasSize(0)));
    }

    @Test
    void 출금일이_지났는데_진행_중인_청구는_오늘_빠질_돈으로_본다() throws Exception {
        long overdue = bill(cardOnA, today.minusDays(3), 0);
        item(overdue, cardOnA, 20_000L, "PENDING");

        mockMvc.perform(get("/api/forecasts/balance").with(AuthUsers.asUser(owner))
                        .param("accountIds", String.valueOf(accountA)).param("until", "DAYS").param("days", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.series[0].date").value(today.toString()))
                .andExpect(jsonPath("$.data.series[0].expected").value(980_000));
    }

    @Test
    void 기간을_보내지_않으면_잔고예보_설정의_기본_일수와_안전_잔액을_쓴다() throws Exception {
        jdbc.update("UPDATE app_user SET forecast_settings = ? WHERE user_id = ?", """
                {"horizon":"DAYS","defaultDays":7,"alertThreshold":5000,"incomeCashflowItemId":null,
                 "dailyLivingCostTarget":null,"includeCandidates":true,"conservativeLine":true,"updatedAt":null}""",
                owner);

        mockMvc.perform(get("/api/forecasts/balance").with(AuthUsers.asUser(owner))
                        .param("accountIds", String.valueOf(accountA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.horizon.days").value(7))
                .andExpect(jsonPath("$.data.alertThreshold").value(5_000));
    }

    @Test
    void 위험_구간과_원인과_계좌별_결과가_응답에_그대로_나간다() throws Exception {
        // 안전 잔액 950,000원: 10일 뒤 청구 60,000원이 빠지면 940,000원으로 기준 아래(확정 선은 그대로라 예상 선 WARNING)
        jdbc.update("UPDATE app_user SET forecast_settings = ? WHERE user_id = ?", """
                {"horizon":"DAYS","defaultDays":14,"alertThreshold":950000,"incomeCashflowItemId":null,
                 "dailyLivingCostTarget":null,"includeCandidates":true,"conservativeLine":true,"updatedAt":null}""",
                owner);
        long bill = bill(cardOnA, today.plusDays(10), 0);
        item(bill, cardOnA, 60_000L, "PENDING");

        mockMvc.perform(get("/api/forecasts/balance").with(AuthUsers.asUser(owner))
                        .param("accountIds", String.valueOf(accountA)).param("includeAccountDetails", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.status").value("WARNING"))
                .andExpect(jsonPath("$.data.summary.largestOutflow.amount").value(-60_000))
                .andExpect(jsonPath("$.data.riskWindows[0].from").value(today.plusDays(10).toString()))
                .andExpect(jsonPath("$.data.riskWindows[0].level").value("WARNING"))
                .andExpect(jsonPath("$.data.riskWindows[0].line").value("EXPECTED"))
                .andExpect(jsonPath("$.data.riskWindows[0].shortfall").value(10_000))
                .andExpect(jsonPath("$.data.riskWindows[0].causes[0].type").value("CARD_BILLING"))
                .andExpect(jsonPath("$.data.riskWindows[0].causes[0].refId").value(bill))
                .andExpect(jsonPath("$.data.series[10].events[0].type").value("CARD_BILLING"))
                .andExpect(jsonPath("$.data.series[10].events[0].confidence").value("EXPECTED"))
                .andExpect(jsonPath("$.data.accountForecasts[0].accountId").value(accountA))
                .andExpect(jsonPath("$.data.accountForecasts[0].summary.status").value("WARNING"))
                .andExpect(jsonPath("$.data.accountForecasts[0].riskWindows[0].level").value("WARNING"));
    }

    @Test
    void 계좌를_쉼표로_이어_보내도_되고_중복은_한_번만_쓴다() throws Exception {
        mockMvc.perform(get("/api/forecasts/balance").with(AuthUsers.asUser(owner))
                        .param("accountIds", accountA + "," + accountB + "," + accountA)
                        .param("until", "DAYS").param("days", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accountIds", hasSize(2)))
                .andExpect(jsonPath("$.data.selectionCount").value(2))
                .andExpect(jsonPath("$.data.currentTotalBalance").value(2_000_000));
    }

    @Test
    void 설정한_월급이_있으면_기간은_다음_월급일_전날까지다() throws Exception {
        LocalDate payday = today.plusDays(5);
        long salary = cashflows.create(owner, new CashflowService.CreateCommand("월급", CashflowItem.Direction.INCOME,
                2_800_000L, payday, CashflowItem.RecurrenceType.MONTHLY, null, null, null, accountA, null, null,
                null)).id();
        jdbc.update("UPDATE app_user SET forecast_settings = ? WHERE user_id = ?", """
                {"horizon":"NEXT_INCOME","defaultDays":30,"alertThreshold":100000,"incomeCashflowItemId":%d,
                 "dailyLivingCostTarget":null,"includeCandidates":true,"conservativeLine":true,"updatedAt":null}"""
                .formatted(salary), owner);

        mockMvc.perform(get("/api/forecasts/balance").with(AuthUsers.asUser(owner))
                        .param("accountIds", String.valueOf(accountA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.horizon.type").value("NEXT_INCOME"))
                .andExpect(jsonPath("$.data.horizon.to").value(payday.minusDays(1).toString()))
                .andExpect(jsonPath("$.data.horizon.days").value(4))
                .andExpect(jsonPath("$.data.series", hasSize(5)));
    }

    @Test
    void 다른_사용자의_청구는_내_예보에_섞이지_않는다() throws Exception {
        // 남의 카드가 내 계좌를 가리키는 데이터는 DB 외래 키(fk_card_account_owner)가 막는다. 남의 계좌·카드의 청구만 둔다.
        OwnershipFixtures fixtures = new OwnershipFixtures(jdbc);
        long stranger = fixtures.user();
        long strangerCard = fixtures.card(stranger, fixtures.product(), fixtures.account(stranger), "ACTIVE");
        long strangerBill = billOf(stranger, strangerCard, today.plusDays(3), 0);
        itemOf(stranger, strangerBill, strangerCard, 40_000L, "PENDING");

        mockMvc.perform(get("/api/forecasts/balance").with(AuthUsers.asUser(owner))
                        .param("accountIds", String.valueOf(accountA)).param("until", "DAYS").param("days", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.series[7].expected").value(1_000_000));
    }

    private long bill(long userCard, LocalDate withdrawalDate, int monthOffset) {
        return billOf(owner, userCard, withdrawalDate, monthOffset);
    }

    /** 같은 카드·같은 청구 월은 하나라 monthOffset으로 청구 월을 나눈다. 이용기간은 테스트에 쓰지 않아 대략 둔다. */
    private long billOf(long user, long userCard, LocalDate withdrawalDate, int monthOffset) {
        LocalDate month = today.withDayOfMonth(1).plusMonths(monthOffset);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return new SimpleJdbcInsert(jdbc).withTableName("card_bill").usingGeneratedKeyColumns("card_bill_id")
                .usingColumns("user_id", "user_card_id", "billing_month", "period_start", "period_end",
                        "withdrawal_date", "status", "created_at", "updated_at")
                .executeAndReturnKey(Map.of("user_id", user, "user_card_id", userCard, "billing_month", month,
                        "period_start", month.minusMonths(1), "period_end", month.minusDays(1),
                        "withdrawal_date", withdrawalDate, "status", "ACCUMULATING", "created_at", now,
                        "updated_at", now))
                .longValue();
    }

    private void item(long cardBill, long userCard, long principal, String itemStatus) {
        itemOf(owner, cardBill, userCard, principal, itemStatus);
    }

    /**
     * 회차는 결제 행을 가리켜야 해서(FK) 회차마다 결제를 하나 만든다. 실제로 회차는 승인된 결제에만 붙으므로 APPROVED로 넣는다
     * (승인 칸은 ck_payment_approval이 함께 요구한다. 실적 인정액은 지금 승인기처럼 0, 일시불).
     */
    private void itemOf(long user, long cardBill, long userCard, long principal, String itemStatus) {
        long product = jdbc.queryForObject("SELECT card_product_id FROM user_card WHERE user_card_id = ?", Long.class,
                userCard);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDate month = jdbc.queryForObject("SELECT billing_month FROM card_bill WHERE card_bill_id = ?",
                LocalDate.class, cardBill);
        long payment = new SimpleJdbcInsert(jdbc).withTableName("payment_transaction")
                .usingGeneratedKeyColumns("payment_transaction_id")
                .usingColumns("user_id", "merchant_id", "user_card_id", "card_product_id", "request_key",
                        "request_fingerprint", "source", "status", "requested_amount", "approved_amount",
                        "installment_months", "recognized_performance_amount", "performance_month", "requested_at",
                        "approved_at", "created_at", "updated_at")
                .executeAndReturnKey(Map.ofEntries(Map.entry("user_id", user), Map.entry("merchant_id", merchant),
                        Map.entry("user_card_id", userCard), Map.entry("card_product_id", product),
                        Map.entry("request_key", UUID.randomUUID().toString()),
                        Map.entry("request_fingerprint", hash()), Map.entry("source", "YAMU"),
                        Map.entry("status", "APPROVED"), Map.entry("requested_amount", principal),
                        Map.entry("approved_amount", principal), Map.entry("installment_months", 1),
                        Map.entry("recognized_performance_amount", 0L), Map.entry("performance_month", month),
                        Map.entry("requested_at", now), Map.entry("approved_at", now), Map.entry("created_at", now),
                        Map.entry("updated_at", now)))
                .longValue();
        new SimpleJdbcInsert(jdbc).withTableName("bill_item")
                .usingColumns("payment_transaction_id", "user_id", "user_card_id", "card_bill_id", "installment_no",
                        "billing_month", "principal_amount", "status", "created_at", "updated_at")
                .execute(Map.of("payment_transaction_id", payment, "user_id", user, "user_card_id", userCard,
                        "card_bill_id", cardBill, "installment_no", 1, "billing_month", month,
                        "principal_amount", principal, "status", itemStatus, "created_at", now, "updated_at", now));
    }

    private long merchant() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return new SimpleJdbcInsert(jdbc).withTableName("merchant").usingGeneratedKeyColumns("merchant_id")
                .usingColumns("merchant_name", "brand_code", "merchant_category_code", "created_at", "updated_at")
                .executeAndReturnKey(Map.of("merchant_name", "테스트 카페", "brand_code", "FORECAST_" + UUID.randomUUID(),
                        "merchant_category_code", "COFFEE_SHOP", "created_at", now, "updated_at", now))
                .longValue();
    }

    private static byte[] hash() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        return ByteBuffer.allocate(32).putLong(a.getMostSignificantBits()).putLong(a.getLeastSignificantBits())
                .putLong(b.getMostSignificantBits()).putLong(b.getLeastSignificantBits()).array();
    }
}
