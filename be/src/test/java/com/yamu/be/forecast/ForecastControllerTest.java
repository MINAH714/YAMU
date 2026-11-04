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

    /** 회차는 결제 행을 가리켜야 해서(FK) 회차마다 청구 전 결제를 하나 만든다. */
    private void itemOf(long user, long cardBill, long userCard, long principal, String itemStatus) {
        long product = jdbc.queryForObject("SELECT card_product_id FROM user_card WHERE user_card_id = ?", Long.class,
                userCard);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        long payment = new SimpleJdbcInsert(jdbc).withTableName("payment_transaction")
                .usingGeneratedKeyColumns("payment_transaction_id")
                .usingColumns("user_id", "merchant_id", "user_card_id", "card_product_id", "request_key",
                        "request_fingerprint", "source", "status", "requested_amount", "requested_at", "created_at",
                        "updated_at")
                .executeAndReturnKey(Map.ofEntries(Map.entry("user_id", user), Map.entry("merchant_id", merchant),
                        Map.entry("user_card_id", userCard), Map.entry("card_product_id", product),
                        Map.entry("request_key", UUID.randomUUID().toString()),
                        Map.entry("request_fingerprint", hash()), Map.entry("source", "YAMU"),
                        Map.entry("status", "REQUESTED"), Map.entry("requested_amount", principal),
                        Map.entry("requested_at", now), Map.entry("created_at", now), Map.entry("updated_at", now)))
                .longValue();
        LocalDate month = jdbc.queryForObject("SELECT billing_month FROM card_bill WHERE card_bill_id = ?",
                LocalDate.class, cardBill);
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
