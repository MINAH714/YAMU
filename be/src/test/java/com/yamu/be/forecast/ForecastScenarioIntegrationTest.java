package com.yamu.be.forecast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.yamu.be.account.AccountBalance;
import com.yamu.be.account.AccountBalanceService;
import com.yamu.be.cashflow.CashflowItem;
import com.yamu.be.cashflow.CashflowService;
import com.yamu.be.global.finapi.FinApiException;
import com.yamu.be.payment.bank.SsafyTransferApi;
import com.yamu.be.support.AuthUsers;
import com.yamu.be.support.MySqlTestSupport;
import com.yamu.be.support.OwnershipFixtures;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 잔고예보·가상 구매 통합 시나리오(Task 7.2.3, S15P31E103-133). 한 사용자에게 예정 수입·지출, 확정·진행 중 카드 청구,
 * 체크카드 생활비, 신용카드 마감 전 사용 추정을 함께 넣고 HTTP 요청부터 실제 MySQL까지 확인한다.
 *
 * <p>실제로 도는 것: 시큐리티 필터 체인(로그인 사용자는 {@link AuthUsers}로 넣는다. Bearer JWT 발급·검증은 이 테스트에서 하지 않고
 * AuthIntegrationTest 등이 확인한다), 잔고예보·가상 구매 서비스, BE 2 청구 원천(card_bill·bill_item), BE 1 카드 조회,
 * 가계부, 소비 분류·생활비 원천, BE 3 추천 엔진(시드 카탈로그 01·02·03). 가짜로 두는 것: 금융망 잔액(BE 1
 * {@link AccountBalanceService})과, 부르지 않아야 하는 금융망 이체({@link SsafyTransferApi}). AI 서버 주소가 없어 생활비는 TSB다.
 *
 * <p>테스트 트랜잭션으로 감싸지 않는다. 추천 엔진이 금융망 잔액을 DB 트랜잭션 밖에서 읽도록 막아 두었고, 실제 요청에도
 * 트랜잭션이 없기 때문이다. 대신 테스트마다 새 사용자를 만들어 서로 섞이지 않게 한다.
 *
 * <p>오늘은 2026-11-06(금, 한국 10시)로 고정한다. 신용카드 1(신한 Mr.Life)은 결제일 25일·마감 14일이라 이번 이용기간은
 * 10/12~11/11, 출금일은 11/25(수)다. 신용카드 2(삼성 iD ON)는 결제일 10일이고 11/10에 확정 청구 80,000원이 있다.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.application.name=yamu-forecast-scenario-test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(ForecastScenarioIntegrationTest.FixedClock.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ForecastScenarioIntegrationTest extends MySqlTestSupport {
    static final LocalDate TODAY = LocalDate.of(2026, 11, 6);
    static final LocalDate RENT_DAY = LocalDate.of(2026, 11, 10);
    static final LocalDate PAYDAY = LocalDate.of(2026, 11, 25);
    static final long BALANCE = 1_000_000L;
    static final long RENT = 500_000L;
    static final long SALARY = 2_500_000L;
    static final long CONFIRMED_BILL = 80_000L;
    /** 생활비(LIVING)로 분류되는 금액. 이 사용자의 직전 3개월 생활 결제 중앙값(4,000원)의 10배 이상이면 큰 지출(LARGE)이다. */
    static final long GYM_FEE = 30_000L;
    /** 신용카드 1의 하루 사용액. 이번 이용기간(10/12~11/6) 26일치가 진행 중 청구서에 있다. */
    static final long CREDIT_DAILY = 4_000L;
    static final long ACCUMULATING_BILL = CREDIT_DAILY * 26;
    /** 마감 전 사용 추정: 11/7~11/11 5일 × 하루 예상 4,000원(테스트는 AI 서버가 없어 TSB, S15P31E103-405). */
    static final long CREDIT_ESTIMATE = CREDIT_DAILY * 5;

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock scenarioClock() {
            return Clock.fixed(Instant.parse("2026-11-06T01:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired CashflowService cashflows;
    @MockitoBean AccountBalanceService balances;
    @MockitoBean SsafyTransferApi transfers;

    long owner;
    long accountA;
    long checkCard;
    long creditCard;
    long creditCard2;
    long gs25;

    @BeforeAll
    void loadCatalog() throws Exception {
        for (String name : List.of("01-card-catalog.sql", "02-card-catalog-ab20.sql", "03-benefit-composition.sql")) {
            try (Connection connection = dataSource.getConnection()) {
                ScriptUtils.executeSqlScript(connection,
                        new EncodedResource(new FileSystemResource(Path.of("seed", name)), StandardCharsets.UTF_8));
            }
        }
    }

    @BeforeEach
    void setUp() {
        OwnershipFixtures ownership = new OwnershipFixtures(jdbc);
        owner = ownership.user();
        accountA = ownership.account(owner);
        stubBalances(owner);
        gs25 = jdbc.queryForObject("SELECT merchant_id FROM merchant WHERE brand_code = 'GS25'", Long.class);

        // 체크카드: 11/1 등록, 등록 전 8~10월 SEED 생활 소비(날마다 9,000~11,000원)
        checkCard = card(owner, product("KB국민 노리 체크"), accountA, LocalDate.of(2026, 11, 1), null);
        for (LocalDate day = LocalDate.of(2026, 8, 1); day.isBefore(LocalDate.of(2026, 11, 1)); day = day.plusDays(1)) {
            payment(owner, checkCard, 9_000L + (day.getDayOfMonth() % 3) * 1_000L, day, "SEED");
        }
        // 신용카드 1: 결제일 25일, 8/1부터 오늘까지 날마다 4,000원(YAMU). 이번 이용기간분은 진행 중 청구서 회차로도 붙인다
        creditCard = card(owner, product("신한카드 Mr.Life"), accountA, LocalDate.of(2026, 7, 1), 25);
        long accumulating = bill(owner, creditCard, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 10, 12),
                LocalDate.of(2026, 11, 11), PAYDAY, "ACCUMULATING", null);
        for (LocalDate day = LocalDate.of(2026, 8, 1); !day.isAfter(TODAY); day = day.plusDays(1)) {
            long payment = payment(owner, creditCard, CREDIT_DAILY, day, "YAMU");
            if (!day.isBefore(LocalDate.of(2026, 10, 12))) {
                billItem(owner, creditCard, accumulating, payment, CREDIT_DAILY);
            }
        }
        // 신용카드 2: 결제일 10일, 11/10 출금 확정 청구 80,000원(이용기간 9/27~10/27의 10/15 결제 한 건이 BILLED 회차).
        // 이번 이용기간(10/28~)은 사용 기록이 없고 출금일(12/10)도 기간 밖이라 알림이 없다
        creditCard2 = card(owner, product("삼성 iD ON 카드"), accountA, LocalDate.of(2026, 7, 1), 10);
        long confirmed = bill(owner, creditCard2, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 9, 27),
                LocalDate.of(2026, 10, 27), RENT_DAY, "CONFIRMED", CONFIRMED_BILL);
        long billedPayment = payment(owner, creditCard2, CONFIRMED_BILL, LocalDate.of(2026, 10, 15), "YAMU");
        // 확정 회차는 청구액이 정해져 있다(일시불은 수수료 0, BillItem.bill()은 청구액 없는 회차를 확정하지 않음)
        insert("bill_item", "bill_item_id", Map.of("payment_transaction_id", billedPayment, "user_id", owner,
                "user_card_id", creditCard2, "card_bill_id", confirmed, "installment_no", 1,
                "billing_month", LocalDate.of(2026, 11, 1), "principal_amount", CONFIRMED_BILL, "fee_amount", 0L,
                "billed_amount", CONFIRMED_BILL, "status", "BILLED"));

        // 추천 엔진이 혜택을 계산하려면 전월(10월) 실적과 이번 달 한도 사용량이 있어야 한다(시드 추천 테스트와 같은 방식)
        for (long card : List.of(checkCard, creditCard, creditCard2)) {
            performanceAndLimits(card);
        }

        // 예정 지출(월세)·예정 수입(월급), 둘 다 A 계좌
        cashflows.create(owner, new CashflowService.CreateCommand("월세", CashflowItem.Direction.EXPENSE,
                RENT, RENT_DAY, CashflowItem.RecurrenceType.NONE, null, null, null, accountA, null, null, null));
        cashflows.create(owner, new CashflowService.CreateCommand("월급", CashflowItem.Direction.INCOME, SALARY, PAYDAY,
                CashflowItem.RecurrenceType.NONE, null, null, null, accountA, null, null, null));
    }

    @Test
    void 예정_항목_확정_진행_중_청구_생활비_신용카드_추정이_각자의_날짜와_선에_들어가고_가상_구매_전_결과는_잔고예보와_같다()
            throws Exception {
        String forecast = forecast(accountA, 30);

        // 11/10: 월세와 확정 청구는 확정 선부터
        assertThat(lineOn(forecast, RENT_DAY, "confirmed")).isEqualTo(BALANCE - RENT - CONFIRMED_BILL);
        assertThat(lineOn(forecast, RENT_DAY, "expected")).isEqualTo(BALANCE - RENT - CONFIRMED_BILL);
        // 11/25: 월급은 확정 선, 진행 중 청구는 예상 선부터, 신용카드 추정은 보수적 선에만
        assertThat(lineOn(forecast, PAYDAY, "confirmed")).isEqualTo(BALANCE - RENT - CONFIRMED_BILL + SALARY);
        assertThat(lineOn(forecast, PAYDAY, "expected"))
                .isEqualTo(BALANCE - RENT - CONFIRMED_BILL + SALARY - ACCUMULATING_BILL);
        List<Map<String, Object>> paydayEvents = JsonPath.read(forecast,
                "$.data.series[?(@.date == '" + PAYDAY + "')].events[*]");
        assertThat(paydayEvents).extracting(e -> e.get("type"), e -> e.get("confidence"), e -> e.get("amount"))
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("SCHEDULED_INCOME", "CONFIRMED", (int) SALARY),
                        org.assertj.core.groups.Tuple.tuple("CARD_BILLING", "EXPECTED", (int) -ACCUMULATING_BILL),
                        org.assertj.core.groups.Tuple.tuple("CARD_SPENDING_ESTIMATE", "CONSERVATIVE",
                                (int) -CREDIT_ESTIMATE));
        // 보수적 선: 11/24 → 11/25에 월급 − 진행 중 청구 − 신용카드 추정 − 하루 생활비만큼 바뀐다
        long livingPerDay = lineOn(forecast, TODAY, "conservative") - lineOn(forecast, TODAY.plusDays(1), "conservative");
        assertThat(livingPerDay).isPositive();
        assertThat(lineOn(forecast, PAYDAY, "conservative") - lineOn(forecast, PAYDAY.minusDays(1), "conservative"))
                .isEqualTo(SALARY - ACCUMULATING_BILL - CREDIT_ESTIMATE - livingPerDay);
        assertThat((String) JsonPath.read(forecast, "$.data.livingCost.method")).isEqualTo("TSB");
        assertThat((String) JsonPath.read(forecast, "$.data.livingCost.dataOrigin")).isEqualTo("SEED");
        // 예정 출금 합계에는 추정을 세지 않는다
        assertThat(((Number) JsonPath.read(forecast, "$.data.summary.largestOutflow.amount")).longValue())
                .isEqualTo(-(RENT + CONFIRMED_BILL));

        // 같은 계좌·기간의 가상 구매: 구매 전 선은 잔고예보의 가장 나쁜 선(보수적)과 날마다 같다
        String simulation = simulate(checkPurchase(300_000));
        List<Number> before = JsonPath.read(simulation, "$.data.series[*].before");
        List<Number> conservative = JsonPath.read(forecast, "$.data.series[*].conservative");
        assertThat(before).extracting(Number::longValue)
                .containsExactlyElementsOf(conservative.stream().map(Number::longValue).toList());
        assertThat(((Number) JsonPath.read(simulation, "$.data.comparison.before.endBalance")).longValue())
                .isEqualTo(((Number) JsonPath.read(forecast, "$.data.summary.endBalance")).longValue());
        // 실제 추천 엔진으로 혜택을 계산하고(KB국민 노리 체크 GS25 환급할인), 출금은 즉시할인을 뺀 청구 금액으로 오늘 한 번
        assertThat((Integer) JsonPath.read(simulation, "$.data.card.userCardId")).isEqualTo((int) checkCard);
        assertThat((Object) JsonPath.read(simulation, "$.data.benefit")).isNotNull();
        assertThat(((Number) JsonPath.read(simulation, "$.data.benefit.netBenefit")).doubleValue()).isPositive();
        long charged = chargedAmount(simulation, 300_000);
        assertThat(((Number) JsonPath.read(simulation, "$.data.schedule[0].amount")).longValue()).isEqualTo(-charged);
        List<Number> after = JsonPath.read(simulation, "$.data.series[*].after");
        for (int day = 0; day < before.size(); day++) {
            assertThat(after.get(day).longValue()).isEqualTo(before.get(day).longValue() - charged);
        }
    }

    @Test
    void 매칭_API로_실제_결제와_연결한_카드_예정_지출은_예정_차감과_생활비에서_한_번만_빠지고_같은_시뮬레이션은_다시_불러도_같다()
            throws Exception {
        // 체크카드로 낼 헬스장 회비(11/10)를 예정 지출로 적어 두고, 11/5에 그 카드로 미리 결제했다.
        long gymItem = gymItem();
        long gymPayment = payment(owner, checkCard, GYM_FEE, LocalDate.of(2026, 11, 5), "YAMU");

        // 연결 전: 예정 지출로 한 번, 생활비(체크카드 결제)로 한 번 빠진다
        String unmatched = forecast(accountA, 30);
        assertThat(lineOn(unmatched, RENT_DAY, "confirmed")).isEqualTo(BALANCE - RENT - CONFIRMED_BILL - GYM_FEE);

        confirmMatch(gymItem, RENT_DAY, gymPayment)
                .andExpect(jsonPath("$.data.warnings").isEmpty())
                .andExpect(jsonPath("$.data.forecastTreatment").value("ACTUAL_PAYMENT_ONLY"));

        // 연결 뒤: 예정 차감에서 빠지고, 그 결제는 생활비 계산에서도 빠진다
        String forecast = forecast(accountA, 30);
        assertThat(lineOn(forecast, RENT_DAY, "confirmed")).isEqualTo(BALANCE - RENT - CONFIRMED_BILL);
        assertThat(((Number) JsonPath.read(forecast, "$.data.livingCost.projectedAmount")).longValue())
                .isLessThan(((Number) JsonPath.read(unmatched, "$.data.livingCost.projectedAmount")).longValue());

        String first = simulate(checkPurchase(300_000));
        String second = simulate(checkPurchase(300_000));
        assertThat((Object) JsonPath.read(second, "$.data.series")).isEqualTo(JsonPath.read(first, "$.data.series"));
        assertThat((Object) JsonPath.read(second, "$.data.comparison"))
                .isEqualTo(JsonPath.read(first, "$.data.comparison"));
        // 가상 구매는 한 번만 빠진다(두 번 불러도 쌓이지 않음)
        long charged = chargedAmount(second, 300_000);
        assertThat(((Number) JsonPath.read(second, "$.data.comparison.withinPeriodImpact")).longValue())
                .isEqualTo(-charged);
    }

    @Test
    void 가상_구매는_결제_청구_실적_한도_사용량_가계부_회차_매칭을_바꾸지_않고_금융망_이체를_부르지_않는다() throws Exception {
        // 기존 연결을 지우거나 바꾸는지도 보려고 매칭을 하나 만들어 둔다
        confirmMatch(gymItem(), RENT_DAY, payment(owner, checkCard, GYM_FEE, LocalDate.of(2026, 11, 5), "YAMU"));
        // 소비 분류는 조회할 때 미분류 결제를 채우므로 한 번 조회한 뒤의 상태와 비교한다
        forecast(accountA, 30);
        Map<String, Object> beforeSnapshot = snapshot(owner);

        simulate(checkPurchase(300_000));
        simulate(creditInstallment(1_200_000, 3));

        assertThat(snapshot(owner)).isEqualTo(beforeSnapshot);
        verifyNoInteractions(transfers);
    }

    @Test
    void 신용_할부로_확정_선이_안전_잔액_아래로_내려가면_구매_후는_DANGER다() throws Exception {
        // 9,000,000원 3개월: 1회차 3,000,000원이 11/25에 빠진다(2·3회차는 기간 밖)
        String simulation = simulate(creditInstallment(9_000_000, 3));

        assertThat((String) JsonPath.read(simulation, "$.data.comparison.before.status")).isNotEqualTo("DANGER");
        assertThat((String) JsonPath.read(simulation, "$.data.comparison.after.status")).isEqualTo("DANGER");
        assertThat((String) JsonPath.read(simulation, "$.data.schedule[0].date")).isEqualTo(PAYDAY.toString());
        long perInstallment = -((Number) JsonPath.read(simulation, "$.data.schedule[0].amount")).longValue();
        assertThat(((Number) JsonPath.read(simulation, "$.data.comparison.withinPeriodImpact")).longValue())
                .isEqualTo(-perInstallment);
        long total = chargedAmount(simulation, 9_000_000);
        assertThat(((Number) JsonPath.read(simulation, "$.data.comparison.beyondPeriodImpact")).longValue())
                .isEqualTo(-(total - perInstallment));
    }

    @Test
    void 금융망_잔액을_못_읽으면_예보와_가상_구매_모두_일부만_계산하지_않고_실패한다() throws Exception {
        when(balances.getBalances(eq(owner), anyList(), anyBoolean()))
                .thenThrow(FinApiException.timedOut("inquireDemandDepositAccountBalance"));

        mockMvc.perform(get("/api/forecasts/balance").with(AuthUsers.asUser(owner))
                        .param("accountIds", String.valueOf(accountA)).param("until", "DAYS").param("days", "30"))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.error.code").value("UPSTREAM_TIMEOUT"));
        mockMvc.perform(post("/api/forecasts/simulations").with(AuthUsers.asUser(owner))
                        .contentType(MediaType.APPLICATION_JSON).content(checkPurchase(300_000)))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.error.code").value("UPSTREAM_TIMEOUT"));
    }

    @Test
    void 생활비_자료가_없는_신용카드_전용_계좌는_추정만으로_보수적_선을_내고_기록_없는_카드는_알린다() throws Exception {
        long accountB = new OwnershipFixtures(jdbc).account(owner);
        // B: 체크카드 없음. 신용카드 3은 날마다 3,000원(8/1~), 신용카드 4는 이번 달 등록이라 완료한 달이 없다
        long card3 = card(owner, product("신한카드 Mr.Life"), accountB, LocalDate.of(2026, 7, 1), 25);
        for (LocalDate day = LocalDate.of(2026, 8, 1); !day.isAfter(TODAY); day = day.plusDays(1)) {
            payment(owner, card3, 3_000L, day, "YAMU");
        }
        card(owner, product("삼성 iD ON 카드"), accountB, LocalDate.of(2026, 11, 1), 25);

        String forecast = forecast(accountB, 30);

        assertThat((String) JsonPath.read(forecast, "$.data.livingCost.dataOrigin")).isEqualTo("INSUFFICIENT");
        // 생활비 0원으로 계산: 11/25에 3,000원 × 5일만 보수적 선에서 빠진다
        assertThat(lineOn(forecast, PAYDAY.minusDays(1), "conservative")).isEqualTo(BALANCE);
        assertThat(lineOn(forecast, PAYDAY, "conservative")).isEqualTo(BALANCE - 15_000L);
        assertThat(lineOn(forecast, PAYDAY, "expected")).isEqualTo(BALANCE);
        List<Map<String, Object>> excluded = JsonPath.read(forecast, "$.data.excluded");
        assertThat(excluded).extracting(e -> e.get("reason"), e -> e.get("date"))
                .containsExactly(org.assertj.core.groups.Tuple.tuple("CREDIT_SPENDING_UNKNOWN", PAYDAY.toString()));
    }

    // ---- 요청 ----

    private String forecast(long accountId, int days) throws Exception {
        return mockMvc.perform(get("/api/forecasts/balance").with(AuthUsers.asUser(owner))
                        .param("accountIds", String.valueOf(accountId)).param("until", "DAYS")
                        .param("days", String.valueOf(days)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String simulate(String body) throws Exception {
        return mockMvc.perform(post("/api/forecasts/simulations").with(AuthUsers.asUser(owner))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** 예정 지출 회차와 결제를 매칭 API로 연결한다. */
    private ResultActions confirmMatch(long cashflowItemId, LocalDate occurrenceDate, long paymentId) throws Exception {
        return mockMvc.perform(post("/api/cashflow-payment-matches").with(AuthUsers.asUser(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cashflowItemId\": %d, \"occurrenceDate\": \"%s\", \"paymentId\": %d}"
                                .formatted(cashflowItemId, occurrenceDate, paymentId)))
                .andExpect(status().isCreated());
    }

    /**
     * 체크카드로 낼 헬스장 회비(11/10, 일회) 예정 지출. 가맹점은 시나리오 공통 GS25다. 업종을 지정하지 않아 매칭 판정에는
     * 영향이 없다.
     */
    private long gymItem() {
        return cashflows.create(owner, new CashflowService.CreateCommand("헬스장 회비", CashflowItem.Direction.EXPENSE,
                GYM_FEE, RENT_DAY, CashflowItem.RecurrenceType.NONE, null, null, null, null, checkCard, null, null)).id();
    }

    /**
     * GS25(편의점)에서 체크카드로 일시불, A 계좌·30일. 잔고예보 조회처럼 until=DAYS를 보내야 사용자 설정과 상관없이 30일이다
     * (-318 이후 until이 없으면 설정을 따름. 그 전 서버는 모르는 칸이라 무시함).
     */
    private String checkPurchase(long amount) {
        return """
                {"accountIds": [%d], "amount": %d, "userCardId": %d, "plan": "LUMP_SUM", "until": "DAYS",
                 "days": 30, "merchantCategoryCode": "CONVENIENCE_STORE", "merchantId": %d, "paymentChannel": "OFFLINE"}"""
                .formatted(accountA, amount, checkCard, gs25);
    }

    /** GS25(편의점)에서 신용카드 1로 할부, A 계좌·30일. */
    private String creditInstallment(long amount, int months) {
        return """
                {"accountIds": [%d], "amount": %d, "userCardId": %d, "plan": "INSTALLMENT", "installmentMonths": %d,
                 "until": "DAYS", "days": 30, "merchantCategoryCode": "CONVENIENCE_STORE", "merchantId": %d,
                 "paymentChannel": "OFFLINE"}"""
                .formatted(accountA, amount, creditCard, months, gs25);
    }

    /** 즉시할인을 뺀 청구 금액. 엔진이 혜택을 계산하지 못했으면 결제 금액 그대로다. */
    private static long chargedAmount(String simulation, long amount) {
        Object benefit = JsonPath.read(simulation, "$.data.benefit");
        return benefit == null ? amount : ((Number) JsonPath.read(simulation, "$.data.benefit.chargedAmount")).longValue();
    }

    private static long lineOn(String forecast, LocalDate date, String line) {
        List<Number> values = JsonPath.read(forecast, "$.data.series[?(@.date == '" + date + "')]." + line);
        assertThat(values).as("%s %s", date, line).hasSize(1);
        return values.get(0).longValue();
    }

    // ---- 데이터 ----

    private void stubBalances(long user) {
        LocalDateTime fetchedAt = LocalDateTime.of(2026, 11, 6, 0, 59);
        when(balances.getBalances(eq(user), anyList(), anyBoolean())).thenAnswer(invocation -> {
            List<Long> ids = invocation.getArgument(1);
            return ids.stream().map(id -> new AccountBalance(id, BALANCE, fetchedAt)).toList();
        });
        // 추천 엔진의 체크카드 결제 가능 판정도 같은 잔액을 본다
        when(balances.getBalanceResults(eq(user), anyList())).thenAnswer(invocation -> {
            List<Long> ids = invocation.getArgument(1);
            Map<Long, AccountBalanceService.BalanceRead> reads = new HashMap<>();
            ids.forEach(id -> reads.put(id,
                    new AccountBalanceService.BalanceRead(new AccountBalance(id, BALANCE, fetchedAt), null)));
            return reads;
        });
    }

    /** 10월 실적 40만 원, 11월 실적 0원, 11월 월 한도 사용량 0. */
    private void performanceAndLimits(long card) {
        jdbc.update("INSERT INTO card_monthly_performance(user_card_id, performance_month, recognized_amount, created_at, "
                + "updated_at) VALUES (?, '2026-10-01', 400000, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)), "
                + "(?, '2026-11-01', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))", card, card);
        jdbc.update("INSERT INTO monthly_limit_usage(user_card_id, card_product_id, limit_group_id, usage_month, used_value, "
                + "created_at, updated_at) SELECT ?, g.card_product_id, g.limit_group_id, '2026-11-01', 0, UTC_TIMESTAMP(6), "
                + "UTC_TIMESTAMP(6) FROM limit_group g JOIN user_card c ON c.card_product_id = g.card_product_id "
                + "WHERE c.user_card_id = ? AND g.period_type = 'MONTHLY'", card, card);
    }

    private long product(String name) {
        return jdbc.queryForObject("SELECT card_product_id FROM card_product WHERE product_name = ?", Long.class, name);
    }

    /** 한국 날짜 registeredOn 정오에 등록한 ACTIVE 카드. 신용카드면 결제일을 넣는다. */
    private long card(long user, long product, long account, LocalDate registeredOn, Integer paymentDay) {
        Map<String, Object> fields = new HashMap<>(Map.of("user_id", user, "card_product_id", product,
                "linked_account_id", account, "card_no_enc", new byte[] {1, 2}, "card_no_hash", hash(),
                "card_last4", "1234", "status", "ACTIVE", "registered_at", SpendingFixtures.utc(registeredOn, 12)));
        if (paymentDay != null) {
            fields.put("payment_day", paymentDay);
        }
        return insert("user_card", "user_card_id", fields);
    }

    /** 한국 날짜 day 오전 9시에 승인된 일시불 결제(오늘 결제도 고정 시각 10시보다 앞). */
    private long payment(long user, long card, long amount, LocalDate day, String source) {
        return new SpendingFixtures(jdbc).payment(user, card, gs25, amount, 1, SpendingFixtures.utc(day, 9), source,
                "APPROVED");
    }

    private long bill(long user, long card, LocalDate billingMonth, LocalDate periodStart, LocalDate periodEnd,
            LocalDate withdrawalDate, String status, Long confirmedAmount) {
        Map<String, Object> fields = new HashMap<>(Map.of("user_id", user, "user_card_id", card,
                "billing_month", billingMonth, "period_start", periodStart, "period_end", periodEnd,
                "withdrawal_date", withdrawalDate, "status", status));
        if (confirmedAmount != null) {
            fields.put("confirmed_amount", confirmedAmount);
            fields.put("linked_account_id", accountA);
            fields.put("confirmed_at", LocalDateTime.of(2026, 10, 27, 15, 30));
        }
        return insert("card_bill", "card_bill_id", fields);
    }

    private void billItem(long user, long card, long cardBill, long payment, long principal) {
        LocalDate month = jdbc.queryForObject("SELECT billing_month FROM card_bill WHERE card_bill_id = ?",
                LocalDate.class, cardBill);
        insert("bill_item", "bill_item_id", Map.of("payment_transaction_id", payment, "user_id", user,
                "user_card_id", card, "card_bill_id", cardBill, "installment_no", 1, "billing_month", month,
                "principal_amount", principal, "status", "PENDING"));
    }

    /** 가상 구매가 바꾸면 안 되는 표들의 이 사용자 행. */
    private Map<String, Object> snapshot(long user) {
        Map<String, Object> tables = new HashMap<>();
        tables.put("payment_transaction", jdbc.queryForList("SELECT payment_transaction_id, status, approved_amount, "
                + "spending_class, updated_at FROM payment_transaction WHERE user_id = ? ORDER BY 1", user));
        tables.put("card_bill", jdbc.queryForList("SELECT card_bill_id, status, confirmed_amount, paid_amount, "
                + "withdrawal_date, updated_at FROM card_bill WHERE user_id = ? ORDER BY 1", user));
        tables.put("bill_item", jdbc.queryForList("SELECT bill_item_id, status, principal_amount, updated_at "
                + "FROM bill_item WHERE user_id = ? ORDER BY 1", user));
        tables.put("card_monthly_performance", jdbc.queryForList("SELECT p.* FROM card_monthly_performance p "
                + "JOIN user_card c ON c.user_card_id = p.user_card_id WHERE c.user_id = ? ORDER BY 1", user));
        tables.put("monthly_limit_usage", jdbc.queryForList("SELECT u.* FROM monthly_limit_usage u "
                + "JOIN user_card c ON c.user_card_id = u.user_card_id WHERE c.user_id = ? ORDER BY 1", user));
        tables.put("payment_limit_usage", jdbc.queryForList("SELECT u.* FROM payment_limit_usage u "
                + "JOIN payment_transaction p ON p.payment_transaction_id = u.payment_transaction_id "
                + "WHERE p.user_id = ? ORDER BY 1", user));
        tables.put("cashflow_item", jdbc.queryForList("SELECT cashflow_item_id, amount, updated_at "
                + "FROM cashflow_item WHERE user_id = ? ORDER BY 1", user));
        tables.put("cashflow_occurrence", jdbc.queryForList("SELECT * FROM cashflow_occurrence WHERE user_id = ? "
                + "ORDER BY 1", user));
        tables.put("cashflow_payment_match", jdbc.queryForList("SELECT * FROM cashflow_payment_match "
                + "WHERE user_id = ? ORDER BY 1", user));
        return tables;
    }

    private long insert(String table, String idColumn, Map<String, Object> fields) {
        Map<String, Object> row = new HashMap<>(fields);
        LocalDateTime now = LocalDateTime.of(2026, 11, 6, 0, 0);
        row.putIfAbsent("created_at", now);
        row.putIfAbsent("updated_at", now);
        return new SimpleJdbcInsert(jdbc).withTableName(table).usingGeneratedKeyColumns(idColumn)
                .usingColumns(row.keySet().toArray(String[]::new)).executeAndReturnKey(row).longValue();
    }

    private static byte[] hash() {
        byte[] bytes = new byte[32];
        UUID id = UUID.randomUUID();
        java.nio.ByteBuffer.wrap(bytes).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits());
        return bytes;
    }
}
