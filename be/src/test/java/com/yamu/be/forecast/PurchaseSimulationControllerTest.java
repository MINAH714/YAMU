package com.yamu.be.forecast;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.yamu.be.account.AccountBalance;
import com.yamu.be.account.AccountBalanceService;
import com.yamu.be.support.AuthUsers;
import com.yamu.be.support.MySqlTestSupport;
import com.yamu.be.support.OwnershipFixtures;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
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
 */
@Transactional
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.application.name=yamu-simulation-api-test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PurchaseSimulationControllerTest extends MySqlTestSupport {
    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean AccountBalanceService balances;
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
        today = LocalDate.now(KOREA);
        checkOnA = spending.card(owner, spending.product("CHECK"), accountA, today.minusDays(1));
        creditOnB = spending.card(owner, spending.product("CREDIT"), accountB, today.minusDays(1));
        jdbc.update("UPDATE user_card SET payment_day = 25 WHERE user_card_id = ?", creditOnB);
        jdbc.update("UPDATE user_card SET card_alias = '생활비 체크' WHERE user_card_id = ?", checkOnA);
        LocalDateTime fetchedAt = LocalDateTime.now(ZoneOffset.UTC);
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
        perform(body(accountA, checkOnA, "300000", "\"days\": 7, \"productName\": \"태블릿\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.input.amount").value(300_000))
                .andExpect(jsonPath("$.data.input.purchaseDate").value(today.toString()))
                .andExpect(jsonPath("$.data.input.plan").value("LUMP_SUM"))
                .andExpect(jsonPath("$.data.card.userCardId").value(checkOnA))
                .andExpect(jsonPath("$.data.card.nickname").value("생활비 체크"))
                .andExpect(jsonPath("$.data.card.cardType").value("CHECK"))
                .andExpect(jsonPath("$.data.card.recommended").value(false))
                // 추천 엔진 연결 전이라 혜택·대안·실적 영향은 모른다(0원으로 확정하지 않음)
                .andExpect(jsonPath("$.data.benefit").value(nullValue()))
                .andExpect(jsonPath("$.data.alternatives").value(nullValue()))
                .andExpect(jsonPath("$.data.performanceImpact").value(nullValue()))
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
                "\"plan\": \"INSTALLMENT\", \"installmentMonths\": 3, \"days\": 7, \"includeAccountDetails\": true"))
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
        perform(body(accountA, creditOnB, "200000", "\"days\": 30"))
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
        when(recommender.recommend(any())).thenReturn(Optional.of(new SimulationRecommender.Recommendation(checkOnA,
                new SimulationRecommender.Benefit(30_000L,
                        List.of(new SimulationRecommender.BenefitItem(140L, "가전 10% 즉시할인", "INSTANT_DISCOUNT", 30_000L)),
                        270_000L),
                List.of(), null)));

        perform(bodyWithoutCard(accountA, "300000", "\"days\": 7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.card.userCardId").value(checkOnA))
                .andExpect(jsonPath("$.data.card.recommended").value(true))
                .andExpect(jsonPath("$.data.benefit.netBenefit").value(30_000))
                .andExpect(jsonPath("$.data.benefit.chargedAmount").value(270_000))
                .andExpect(jsonPath("$.data.schedule[0].amount").value(-270_000))
                .andExpect(jsonPath("$.data.comparison.after.endBalance").value(730_000));
    }

    @Test
    void 추천_엔진_연결_전에_카드를_고르지_않으면_422다() throws Exception {
        perform(bodyWithoutCard(accountA, "300000", ""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("DATA_INSUFFICIENT"))
                .andExpect(jsonPath("$.error.details.reason").value("RECOMMENDATION_UNAVAILABLE"));
        verify(balances, never()).getBalances(any(), anyList(), anyBoolean());
    }

    @Test
    void 물품_업종_가맹점이_모두_없으면_400_MERCHANT_OR_CATEGORY_REQUIRED다() throws Exception {
        perform("""
                {"accountIds": [%d], "userCardId": %d, "amount": 300000}""".formatted(accountA, checkOnA))
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
    void 남의_카드는_404_꺼_둔_카드는_400_결제일_없는_신용카드는_422다() throws Exception {
        OwnershipFixtures ownership = new OwnershipFixtures(jdbc);
        long stranger = ownership.user();
        long strangerCard = ownership.card(stranger, ownership.product(), ownership.account(stranger), "ACTIVE");
        perform(body(accountA, strangerCard, "300000", ""))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));

        jdbc.update("UPDATE user_card SET status = 'INACTIVE' WHERE user_card_id = ?", checkOnA);
        perform(body(accountA, checkOnA, "300000", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.fieldErrors[0].field").value("userCardId"));

        jdbc.update("UPDATE user_card SET payment_day = NULL WHERE user_card_id = ?", creditOnB);
        perform(body(accountB, creditOnB, "300000", ""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.details.reason").value("BILLING_CYCLE_UNKNOWN"));
    }

    @Test
    void 계좌를_고르지_않으면_400_ACCOUNT_SELECTION_REQUIRED다() throws Exception {
        perform("""
                {"userCardId": %d, "amount": 300000, "productCategoryCode": "PRODUCT_ELECTRONICS"}"""
                .formatted(checkOnA))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_SELECTION_REQUIRED"));
    }

    private ResultActions perform(String json) throws Exception {
        return mockMvc.perform(post("/api/forecasts/simulations").with(AuthUsers.asUser(owner))
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    /** 물품 카테고리를 넣은 요청. extra는 쉼표 없이 이어 붙일 필드(빈 문자열이면 없음). */
    private static String body(long account, long card, String amount, String extra) {
        return "{\"accountIds\": [" + account + "], \"userCardId\": " + card + ", \"amount\": " + amount
                + ", \"productCategoryCode\": \"PRODUCT_ELECTRONICS\"" + (extra.isEmpty() ? "" : ", " + extra) + "}";
    }

    private static String bodyWithoutCard(long account, String amount, String extra) {
        return "{\"accountIds\": [" + account + "], \"amount\": " + amount
                + ", \"productCategoryCode\": \"PRODUCT_ELECTRONICS\"" + (extra.isEmpty() ? "" : ", " + extra) + "}";
    }
}
