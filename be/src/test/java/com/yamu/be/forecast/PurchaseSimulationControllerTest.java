package com.yamu.be.forecast;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.yamu.be.account.AccountBalance;
import com.yamu.be.account.AccountBalanceService;
import com.yamu.be.cashflow.CashflowItem;
import com.yamu.be.cashflow.CashflowService;
import com.yamu.be.support.AuthUsers;
import com.yamu.be.support.MySqlTestSupport;
import com.yamu.be.support.OwnershipFixtures;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

/**
 * 가상 구매 시뮬레이션 API를 인증 필터·실제 MySQL까지 묶어 확인한다. 잔액(BE 1, 금융망)과 추천 엔진(연결 전)만 가짜로 둔다.
 * 추천 가짜는 따로 정하지 않으면 empty(Mockito 기본값)라 연결 전 임시 구현과 같다.
 * 시계는 컨텍스트를 만들 때의 시각으로 고정해 테스트와 서비스가 같은 오늘을 본다(자정을 사이에 두고 어긋나지 않게, S15P31E103-327 Codex 검토).
 */
@Transactional
@Import(PurchaseSimulationControllerTest.FixedClock.class)
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.application.name=yamu-simulation-api-test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PurchaseSimulationControllerTest extends MySqlTestSupport {
    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock simulationTestClock() {
            return Clock.fixed(Instant.now(), ZoneOffset.UTC);
        }
    }

    @Autowired MockMvc mockMvc;
    @Autowired Clock clock;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean AccountBalanceService balances;
    @Autowired CashflowService cashflows;
    @MockitoBean SimulationRecommender recommender;

    long owner;
    long accountA;
    long accountB;
    long checkOnA;
    long creditOnB;
    LocalDate today;

    @BeforeEach
    void setUp() {
        OwnershipFixtures ownership = new OwnershipFixtures(jdbc);
        SpendingFixtures spending = new SpendingFixtures(jdbc);
        owner = ownership.user();
        accountA = ownership.account(owner);
        accountB = ownership.account(owner);
        today = LocalDate.now(clock.withZone(KOREA));
        checkOnA = spending.card(owner, spending.product("CHECK"), accountA, today.minusDays(1));
        creditOnB = spending.card(owner, spending.product("CREDIT"), accountB, today.minusDays(1));
        jdbc.update("UPDATE user_card SET payment_day = 25 WHERE user_card_id = ?", creditOnB);
        jdbc.update("UPDATE user_card SET card_alias = '생활비 체크' WHERE user_card_id = ?", checkOnA);
        LocalDateTime fetchedAt = LocalDateTime.now(clock);
        when(balances.getBalances(eq(owner), anyList(), anyBoolean())).thenAnswer(invocation -> {
            List<Long> ids = invocation.getArgument(1);
            return ids.stream().map(id -> new AccountBalance(id, 1_000_000L, fetchedAt)).toList();
        });
    }

    @Test
    void 토큰이_없으면_401이다() throws Exception {
        mockMvc.perform(post("/api/forecasts/simulations").contentType(MediaType.APPLICATION_JSON)
                        .content(body(accountA, checkOnA, "300000", "")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 체크카드는_구매일에_한_번_빠지고_구매_전후를_비교한다() throws Exception {
        perform(body(accountA, checkOnA, "300000", "\"until\": \"DAYS\", \"days\": 7, \"productName\": \"태블릿\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.input.amount").value(300_000))
                .andExpect(jsonPath("$.data.input.purchaseDate").value(today.toString()))
                .andExpect(jsonPath("$.data.input.plan").value("LUMP_SUM"))
                .andExpect(jsonPath("$.data.card.userCardId").value(checkOnA))
                .andExpect(jsonPath("$.data.card.nickname").value("생활비 체크"))
                .andExpect(jsonPath("$.data.card.cardType").value("CHECK"))
                .andExpect(jsonPath("$.data.card.recommended").value(false))
                .andExpect(jsonPath("$.data.input.paymentChannel").value("OFFLINE"))
                // 업종·브랜드가 없어 엔진을 부르지 않는다. 혜택은 모른다(0원으로 확정하지 않음)
                .andExpect(jsonPath("$.data.benefit").value(nullValue()))
                .andExpect(jsonPath("$.data.alternatives").value(nullValue()))
                .andExpect(jsonPath("$.data.performanceImpact").value(nullValue()))
                .andExpect(jsonPath("$.data.missingInformation[0].code").value("MERCHANT_CATEGORY_MISSING"))
                .andExpect(jsonPath("$.data.creditLimit").value(nullValue()))
                .andExpect(jsonPath("$.data.schedule", hasSize(1)))
                .andExpect(jsonPath("$.data.schedule[0].date").value(today.toString()))
                .andExpect(jsonPath("$.data.schedule[0].amount").value(-300_000))
                .andExpect(jsonPath("$.data.schedule[0].label").value("체크카드 즉시 출금"))
                .andExpect(jsonPath("$.data.comparison.before.endBalance").value(1_000_000))
                .andExpect(jsonPath("$.data.comparison.after.endBalance").value(700_000))
                .andExpect(jsonPath("$.data.comparison.withinPeriodImpact").value(-300_000))
                .andExpect(jsonPath("$.data.comparison.beyondPeriodImpact").value(0))
                .andExpect(jsonPath("$.data.series", hasSize(8)))
                .andExpect(jsonPath("$.data.series[0].after").value(700_000))
                .andExpect(jsonPath("$.data.outsideSelectionImpact").value(nullValue()))
                .andExpect(jsonPath("$.data.accountComparisons").value(nullValue()))
                .andExpect(jsonPath("$.data.message").value(startsWith(today.getMonthValue() + "월 ")))
                .andExpect(jsonPath("$.data.isEstimate").value(true));
    }

    @Test
    void 신용_할부는_회차마다_나뉘고_신용한도는_확인_생략이다() throws Exception {
        perform(body(accountB, creditOnB, "300000",
                "\"plan\": \"INSTALLMENT\", \"installmentMonths\": 3, \"until\": \"DAYS\", \"days\": 7, \"includeAccountDetails\": true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.card.cardType").value("CREDIT"))
                .andExpect(jsonPath("$.data.input.installmentMonths").value(3))
                .andExpect(jsonPath("$.data.schedule", hasSize(3)))
                .andExpect(jsonPath("$.data.schedule[0].amount").value(-100_000))
                .andExpect(jsonPath("$.data.schedule[0].label").value(startsWith("1/3회차 (")))
                .andExpect(jsonPath("$.data.schedule[2].label").value(startsWith("3/3회차 (")))
                .andExpect(jsonPath("$.data.creditLimit.status").value("SKIPPED"))
                .andExpect(jsonPath("$.data.creditLimit.availableLimit").value(nullValue()))
                .andExpect(jsonPath("$.data.accountComparisons", hasSize(1)))
                .andExpect(jsonPath("$.data.accountComparisons[0].accountId").value(accountB));
    }

    @Test
    void 카드_출금_계좌를_고르지_않았으면_비교는_같고_선택_밖_영향으로_알린다() throws Exception {
        perform(body(accountA, creditOnB, "200000", "\"until\": \"DAYS\", \"days\": 30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.comparison.before.endBalance").value(1_000_000))
                .andExpect(jsonPath("$.data.comparison.after.endBalance").value(1_000_000))
                .andExpect(jsonPath("$.data.comparison.withinPeriodImpact").value(0))
                .andExpect(jsonPath("$.data.outsideSelectionImpact.accountId").value(accountB))
                .andExpect(jsonPath("$.data.outsideSelectionImpact.amount").value(-200_000))
                .andExpect(jsonPath("$.data.message").value(startsWith("선택하지 않은 계좌에서 200,000원")));
    }

    @Test
    void 추천_엔진이_고른_카드와_즉시할인_뺀_금액으로_계산한다() throws Exception {
        when(recommender.recommend(any())).thenReturn(SimulationRecommender.Result.of(
                new SimulationRecommender.Recommendation(checkOnA,
                        new SimulationRecommender.Benefit(new BigDecimal("30000.5"),
                                List.of(new SimulationRecommender.BenefitItem(140L, "가전 10% 즉시할인", "DISCOUNT",
                                        "AT_APPROVAL", new BigDecimal("30000"))), 30_000L),
                        List.of(), null, List.of(new SimulationRecommender.Assumption("YAMU_PURCHASE_TIME_NOON", "낮 12시")),
                        List.of())));

        perform(bodyWithoutCard(accountA, "300000", "\"until\": \"DAYS\", \"days\": 7, \"merchantCategoryCode\": \"SUPERMARKET\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.card.userCardId").value(checkOnA))
                .andExpect(jsonPath("$.data.card.recommended").value(true))
                .andExpect(jsonPath("$.data.input.merchantCategoryCode").value("SUPERMARKET"))
                .andExpect(jsonPath("$.data.benefit.netBenefit").value(30_000.5))
                .andExpect(jsonPath("$.data.benefit.benefits[0].realization").value("AT_APPROVAL"))
                // 청구 금액은 결제 승인과 같이 결제 금액 − 즉시할인
                .andExpect(jsonPath("$.data.benefit.chargedAmount").value(270_000))
                .andExpect(jsonPath("$.data.schedule[0].amount").value(-270_000))
                .andExpect(jsonPath("$.data.comparison.after.endBalance").value(730_000))
                .andExpect(jsonPath("$.data.assumptions[0].id").value("YAMU_PURCHASE_TIME_NOON"));
        verify(recommender).recommend(argThat(request -> "OFFLINE".equals(request.paymentChannel())
                && "SUPERMARKET".equals(request.merchantCategoryCode()) && request.merchantId() == null));
    }

    @Test
    void 전액_즉시할인으로_청구_금액이_0원이면_출금_회차가_없고_전후가_같다() throws Exception {
        when(recommender.recommend(any())).thenReturn(SimulationRecommender.Result.of(
                new SimulationRecommender.Recommendation(checkOnA,
                        new SimulationRecommender.Benefit(new BigDecimal("300000"),
                                List.of(new SimulationRecommender.BenefitItem(140L, "전액 할인", "DISCOUNT",
                                        "AT_APPROVAL", new BigDecimal("300000"))), 300_000L),
                        List.of(), null, List.of(), List.of())));

        perform(body(accountA, checkOnA, "300000", "\"until\": \"DAYS\", \"days\": 7, \"merchantCategoryCode\": \"SUPERMARKET\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.benefit.chargedAmount").value(0))
                .andExpect(jsonPath("$.data.schedule", hasSize(0)))
                .andExpect(jsonPath("$.data.comparison.after.endBalance").value(1_000_000))
                .andExpect(jsonPath("$.data.comparison.withinPeriodImpact").value(0));
    }

    @Test
    void 추천_엔진이_남의_카드를_대안으로_주면_응답하지_않고_실패한다() throws Exception {
        OwnershipFixtures ownership = new OwnershipFixtures(jdbc);
        long stranger = ownership.user();
        long strangerCard = ownership.card(stranger, ownership.product(), ownership.account(stranger), "ACTIVE");
        when(recommender.recommend(any())).thenReturn(SimulationRecommender.Result.of(
                new SimulationRecommender.Recommendation(checkOnA, null,
                        List.of(new SimulationRecommender.Alternative(strangerCard, "남의 카드",
                                com.yamu.be.card.domain.CardType.CREDIT, "LUMP_SUM", null, new BigDecimal("1000"),
                                null, false)), null, List.of(), List.of())));

        // 엔진 계약 위반이라 500이다. 남의 카드 정보가 응답에 실리지 않는다
        perform(body(accountA, checkOnA, "300000", "\"merchantCategoryCode\": \"SUPERMARKET\""))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void 카드를_고르지_않았는데_업종_브랜드가_없거나_추천할_카드가_없으면_422다() throws Exception {
        perform(bodyWithoutCard(accountA, "300000", ""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("DATA_INSUFFICIENT"))
                .andExpect(jsonPath("$.error.details.reason").value("CATEGORY_REQUIRED"));

        when(recommender.recommend(any())).thenReturn(SimulationRecommender.Result.unavailable("NO_ELIGIBLE_CARD"));
        perform(bodyWithoutCard(accountA, "300000", "\"merchantCategoryCode\": \"SUPERMARKET\""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.details.reason").value("NO_ELIGIBLE_CARD"));

        // 금융망 잔액 확인 실패 등은 혜택 정보 부족과 다른 이유·문구다(!155 리뷰)
        when(recommender.recommend(any())).thenReturn(SimulationRecommender.Result.unavailable("PAYMENT_AVAILABILITY_UNKNOWN"));
        perform(bodyWithoutCard(accountA, "300000", "\"merchantCategoryCode\": \"SUPERMARKET\""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.details.reason").value("PAYMENT_AVAILABILITY_UNKNOWN"))
                .andExpect(jsonPath("$.error.message").value("지금 카드 결제 가능 여부(잔액·한도)를 확인하지 못했어요. 잠시 뒤 다시 시도해 주세요."));
        verify(balances, never()).getBalances(any(), anyList(), anyBoolean());
    }

    @Test
    void 결제_채널이_없거나_다른_값이면_400_INVALID_REQUEST다() throws Exception {
        perform("""
                {"accountIds": [%d], "userCardId": %d, "amount": 300000, "productCategoryCode": "PRODUCT_ELECTRONICS"}"""
                .formatted(accountA, checkOnA))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.details.fieldErrors[0].field").value("paymentChannel"));
        perform(body(accountA, checkOnA, "300000", "").replace("OFFLINE", "PHONE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.fieldErrors[0].field").value("paymentChannel"));
    }

    @Test
    void 물품_업종_가맹점이_모두_없으면_400_MERCHANT_OR_CATEGORY_REQUIRED다() throws Exception {
        perform("""
                {"accountIds": [%d], "userCardId": %d, "amount": 300000, "paymentChannel": "OFFLINE"}"""
                .formatted(accountA, checkOnA))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MERCHANT_OR_CATEGORY_REQUIRED"));
    }

    @Test
    void 금액과_할부_조건이_틀리면_계좌를_읽기_전에_400이다() throws Exception {
        perform(body(accountA, checkOnA, "1500.5", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_AMOUNT"));
        perform(body(accountA, checkOnA, "0", ""))
                .andExpect(jsonPath("$.error.code").value("INVALID_AMOUNT"));
        perform(body(accountA, checkOnA, "100000000001", ""))
                .andExpect(jsonPath("$.error.code").value("INVALID_AMOUNT"));
        // 체크카드 할부, 할부 5만 원 미만, 일시불에 개월 수
        perform(body(accountA, checkOnA, "300000", "\"plan\": \"INSTALLMENT\", \"installmentMonths\": 3"))
                .andExpect(jsonPath("$.error.code").value("INVALID_INSTALLMENT"));
        perform(body(accountB, creditOnB, "49999", "\"plan\": \"INSTALLMENT\", \"installmentMonths\": 3"))
                .andExpect(jsonPath("$.error.code").value("INVALID_INSTALLMENT"));
        perform(body(accountB, creditOnB, "300000", "\"installmentMonths\": 3"))
                .andExpect(jsonPath("$.error.code").value("INVALID_INSTALLMENT"));
        verify(balances, never()).getBalances(any(), anyList(), anyBoolean());
    }

    @Test
    void 지난_구매일과_잘못된_기간은_400_INVALID_REQUEST다() throws Exception {
        perform(body(accountA, checkOnA, "300000", "\"purchaseDate\": \"" + today.minusDays(1) + "\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.details.fieldErrors[0].field").value("purchaseDate"));
        perform(body(accountA, checkOnA, "300000", "\"days\": 10"))
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.details.fieldErrors[0].field").value("days"));
    }

    @Test
    void 아주_먼_구매일은_신용카드여도_500이_아니라_400이고_잔액과_추천을_읽지_않는다() throws Exception {
        // 상한이 없을 때는 청구 주기 계산이 999999999년 12월의 다음 달을 구하다 DateTimeException으로 500이었다(S15P31E103-327)
        perform(body(accountB, creditOnB, "300000", "\"purchaseDate\": \"+999999999-12-31\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.details.fieldErrors[0].field").value("purchaseDate"));
        verify(balances, never()).getBalances(any(), anyList(), anyBoolean());
        verify(recommender, never()).recommend(any());
    }

    @Test
    void 구매일은_오늘부터_1년째_되는_날까지_받고_그다음_날은_400이다() throws Exception {
        LocalDate lastDay = today.plusYears(1);
        perform(body(accountB, creditOnB, "300000",
                "\"plan\": \"INSTALLMENT\", \"installmentMonths\": 12, \"purchaseDate\": \"" + lastDay + "\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.input.purchaseDate").value(lastDay.toString()))
                .andExpect(jsonPath("$.data.schedule", hasSize(12)));
        perform(body(accountB, creditOnB, "300000", "\"purchaseDate\": \"" + lastDay.plusDays(1) + "\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.details.fieldErrors[0].field").value("purchaseDate"));
        // 체크카드는 청구 주기 계산이 없어도 같은 상한을 받는다(!173 리뷰)
        perform(body(accountA, checkOnA, "300000", "\"purchaseDate\": \"" + lastDay.plusDays(1) + "\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.details.fieldErrors[0].field").value("purchaseDate"));
    }

    @Test
    void 남의_카드는_404_꺼_둔_카드와_정지_카드는_결제와_같은_400_CARD_NOT_ELIGIBLE_결제일_없는_신용카드는_422다()
            throws Exception {
        OwnershipFixtures ownership = new OwnershipFixtures(jdbc);
        long stranger = ownership.user();
        long strangerCard = ownership.card(stranger, ownership.product(), ownership.account(stranger), "ACTIVE");
        perform(body(accountA, strangerCard, "300000", ""))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));

        jdbc.update("UPDATE user_card SET status = 'INACTIVE' WHERE user_card_id = ?", checkOnA);
        perform(body(accountA, checkOnA, "300000", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("CARD_NOT_ELIGIBLE"))
                .andExpect(jsonPath("$.error.details.reason").value("CARD_INACTIVE"));

        // 같은 트랜잭션에서 이미 읽은 카드는 JPA가 기억하므로, 정지 카드는 새 카드로 확인한다
        long suspended = new SpendingFixtures(jdbc).card(owner, new SpendingFixtures(jdbc).product("CHECK"), accountA,
                today.minusDays(1));
        jdbc.update("UPDATE user_card SET issuer_status = 'SUSPENDED' WHERE user_card_id = ?", suspended);
        perform(body(accountA, suspended, "300000", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("CARD_NOT_ELIGIBLE"))
                .andExpect(jsonPath("$.error.details.reason").value("CARD_SUSPENDED"));

        jdbc.update("UPDATE user_card SET payment_day = NULL WHERE user_card_id = ?", creditOnB);
        perform(body(accountB, creditOnB, "300000", ""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.details.reason").value("BILLING_CYCLE_UNKNOWN"));
    }

    @Test
    void 미납_청구서가_있는_신용카드는_실제_승인처럼_400_CARD_NOT_ELIGIBLE_UNPAID_BILL이다() throws Exception {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDate lastMonth = today.minusMonths(1).withDayOfMonth(1);
        jdbc.update("""
                INSERT INTO card_bill (user_id, user_card_id, linked_account_id, billing_month, period_start, period_end,
                    withdrawal_date, status, confirmed_amount, confirmed_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'UNPAID', 50000, ?, ?, ?)
                """, owner, creditOnB, accountB, lastMonth, lastMonth.minusMonths(1), lastMonth.minusDays(1),
                lastMonth.withDayOfMonth(25), now, now, now);

        perform(body(accountB, creditOnB, "300000", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("CARD_NOT_ELIGIBLE"))
                .andExpect(jsonPath("$.error.details.reason").value("UNPAID_BILL"));
    }

    @Test
    void 계좌를_고르지_않으면_400_ACCOUNT_SELECTION_REQUIRED다() throws Exception {
        perform("""
                {"userCardId": %d, "amount": 300000, "productCategoryCode": "PRODUCT_ELECTRONICS",
                 "paymentChannel": "OFFLINE"}"""
                .formatted(checkOnA))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_SELECTION_REQUIRED"));
    }

    @Test
    void 계좌_카드_가맹점_ID에_소수점_아래가_있으면_다른_ID로_계산하지_않고_400이다() throws Exception {
        // S15P31E103-318: 전에는 merchantId 2.9를 2번 가맹점으로, 계좌 3.5를 3번 계좌로 계산했다
        perform("{\"accountIds\": [" + accountA + ".5], \"userCardId\": " + checkOnA + ", \"amount\": 300000, "
                + "\"productCategoryCode\": \"PRODUCT_ELECTRONICS\", \"paymentChannel\": \"OFFLINE\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        perform(body(accountA, checkOnA, "300000", "\"merchantId\": 2.9"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        perform(body(accountA, checkOnA, "300000", "").replace("\"userCardId\": " + checkOnA, "\"userCardId\": " + checkOnA + ".2"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        // 값이 정수인 소수 표기는 받는다
        perform(body(accountA, checkOnA, "300000", "\"until\": \"DAYS\", \"days\": 7").replace("\"userCardId\": " + checkOnA, "\"userCardId\": " + checkOnA + ".0"))
                .andExpect(status().isOk());
    }

    @Test
    void 같은_until_days로_부르면_가상_구매와_잔고예보의_기간이_같다() throws Exception {
        // S15P31E103-318. 설정은 다음 월급일 기준이고 월급일은 5일 뒤
        LocalDate payday = today.plusDays(5);
        long salary = cashflows.create(owner, new CashflowService.CreateCommand("월급", CashflowItem.Direction.INCOME,
                2_000_000L, payday, CashflowItem.RecurrenceType.MONTHLY, null, null, null, accountA, null, null,
                null)).id();
        jdbc.update("UPDATE app_user SET forecast_settings = ? WHERE user_id = ?", """
                {"horizon":"NEXT_INCOME","defaultDays":30,"alertThreshold":100000,"incomeCashflowItemId":%d,
                 "dailyLivingCostTarget":null,"includeCandidates":true,"conservativeLine":true,"updatedAt":null}"""
                .formatted(salary), owner);

        // until 없이 days만: 둘 다 설정(다음 월급일 전날까지)을 따르고 days는 쓰지 않는다
        perform(body(accountA, checkOnA, "300000", "\"days\": 7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.series", hasSize(5)))
                .andExpect(jsonPath("$.data.series[4].date").value(payday.minusDays(1).toString()));
        mockMvc.perform(get("/api/forecasts/balance").with(AuthUsers.asUser(owner))
                        .param("accountIds", String.valueOf(accountA)).param("days", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.series", hasSize(5)))
                .andExpect(jsonPath("$.data.horizon.to").value(payday.minusDays(1).toString()));

        // until=DAYS: 둘 다 7일(오늘 포함 8개 점)
        perform(body(accountA, checkOnA, "300000", "\"until\": \"DAYS\", \"days\": 7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.series", hasSize(8)));
        mockMvc.perform(get("/api/forecasts/balance").with(AuthUsers.asUser(owner))
                        .param("accountIds", String.valueOf(accountA)).param("until", "DAYS").param("days", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.series", hasSize(8)));
    }

    @Test
    void until_값이_틀리면_400이다() throws Exception {
        // 업종이 있어 until 말고는 추천 엔진까지 가는 요청이다.
        String category = ", \"merchantCategoryCode\": \"SUPERMARKET\"";
        perform(body(accountA, checkOnA, "300000", "\"until\": \"WEEK\"" + category))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.details.fieldErrors[0].field").value("until"));
        // 숫자는 enum 순번(0 = NEXT_INCOME)으로 읽지 않는다.
        for (String value : new String[] {"0", "1", "\"days\""}) {
            perform(body(accountA, checkOnA, "300000", "\"until\": " + value + ", \"days\": 7" + category))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.details.fieldErrors[0].field").value("until"));
        }
        // 요청만으로 알 수 있는 오류라 days처럼 추천 엔진을 부르기 전에 거절한다(!164 리뷰).
        verify(recommender, never()).recommend(any());
    }

    private ResultActions perform(String json) throws Exception {
        return mockMvc.perform(post("/api/forecasts/simulations").with(AuthUsers.asUser(owner))
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    /** 물품 카테고리·매장 결제를 넣은 요청. extra는 쉼표 없이 이어 붙일 필드(빈 문자열이면 없음). */
    private static String body(long account, long card, String amount, String extra) {
        return "{\"accountIds\": [" + account + "], \"userCardId\": " + card + ", \"amount\": " + amount
                + ", \"productCategoryCode\": \"PRODUCT_ELECTRONICS\", \"paymentChannel\": \"OFFLINE\""
                + (extra.isEmpty() ? "" : ", " + extra) + "}";
    }

    private static String bodyWithoutCard(long account, String amount, String extra) {
        return "{\"accountIds\": [" + account + "], \"amount\": " + amount
                + ", \"productCategoryCode\": \"PRODUCT_ELECTRONICS\", \"paymentChannel\": \"OFFLINE\""
                + (extra.isEmpty() ? "" : ", " + extra) + "}";
    }
}
