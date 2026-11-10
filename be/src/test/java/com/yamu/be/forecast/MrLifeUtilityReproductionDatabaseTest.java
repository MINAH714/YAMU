package com.yamu.be.forecast;

import static org.assertj.core.api.Assertions.assertThat;

import com.yamu.be.global.crypto.CipherField;
import com.yamu.be.global.crypto.PersonalDataCipher;
import com.yamu.be.payment.PaymentTerms;
import com.yamu.be.payment.recommend.CardRecommender;
import com.yamu.be.payment.recommend.RecommendationRequest;
import com.yamu.be.recommendation.BasicPurchaseSimulation;
import com.yamu.be.recommendation.CalculationOptions;
import com.yamu.be.recommendation.CardBenefitCalculator;
import com.yamu.be.recommendation.RecommendationInput;
import com.yamu.be.recommendation.RecommendationService;
import com.yamu.be.support.MySqlTestSupport;
import com.yamu.be.support.OwnershipFixtures;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** B-07: 실제 Seed 브랜드 미선택 및 POS 추천 회귀. */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = "spring.application.name=simulation-brand-missing")
@Import(MrLifeUtilityReproductionDatabaseTest.TimeConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MrLifeUtilityReproductionDatabaseTest extends MySqlTestSupport {

    /** 2026-11-03 12:00 KST. 가상 구매는 구매일 낮 12시로 가정하므로 POS 요청 시각과 같다. */
    static final Instant NOW = Instant.parse("2026-11-03T03:00:00Z");
    static final LocalDate TODAY = LocalDate.of(2026, 11, 3);
    static final long AMOUNT = 129_000L;

    @TestConfiguration
    static class TimeConfig {
        @Bean
        @Primary
        Clock simulationPosFactsClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired DataSource source;
    @Autowired JdbcTemplate jdbc;
    @Autowired SimulationRecommender simulation;
    @Autowired CardRecommender pos;
    @Autowired RecommendationService engine;
    @Autowired PersonalDataCipher cipher;
    @MockitoBean com.yamu.be.account.SsafyAccountApi bank;

    @BeforeAll
    void seed() throws Exception {
        for (var name : List.of("01-card-catalog.sql", "02-card-catalog-ab20.sql", "03-benefit-composition.sql")) {
            try (var connection = source.getConnection()) {
                ScriptUtils.executeSqlScript(connection,
                        new EncodedResource(new FileSystemResource(Path.of("seed", name)), StandardCharsets.UTF_8));
            }
        }
    }

    @BeforeEach
    void balances() {
        Mockito.when(bank.balance(ArgumentMatchers.anyString(), ArgumentMatchers.anyString())).thenReturn(1_000_000L);
    }

    @Test
    void brandMissingCompletesBothCardsAndAutomaticRecommendation() {
        long user = new OwnershipFixtures(jdbc).user();
        long mrLife = card(user, "신한카드 Mr.Life");
        long nori = card(user, "KB국민 노리 체크");
        var automatic = simulation.recommend(request(user, null, null));
        assertThat(automatic.unavailableReason()).isNull();
        assertThat(automatic.recommendation().benefit()).isNotNull();
        for (long cardId : List.of(mrLife, nori)) {
            var selected = simulation.recommend(request(user, cardId, null)).recommendation();
            assertThat(selected.benefit()).isNotNull();
            assertThat(selected.benefit().netBenefit()).isZero();
            // 실적 인정액 안내는 이번 혜택 계산과 별개이며 숨기지 않는다.
            assertThat(selected.missingInformation()).extracting(SimulationRecommender.MissingInformation::code)
                    .doesNotContain("BRAND_MISSING", "FACT_MISSING", "BENEFIT_DATA_INSUFFICIENT");
            assertThat(selected.assumptions()).extracting(SimulationRecommender.Assumption::id)
                    .contains("YAMU_BRAND_NOT_SELECTED");
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"EMART", "CATEGORY_ONLY", "KT"})
    void reproducesUtilityDecision(String brand) {
        long user = new OwnershipFixtures(jdbc).user();
        long mr = card(user, "신한카드 Mr.Life");
        Long merchant = brand.equals("CATEGORY_ONLY") ? null : jdbc.queryForObject(
                "SELECT merchant_id FROM merchant WHERE brand_code=?", Long.class, brand);
        String category = merchant == null ? "HYPERMARKET" : jdbc.queryForObject(
                "SELECT merchant_category_code FROM merchant WHERE merchant_id=?", String.class, merchant);
        LocalDate date = LocalDate.of(2026, 11, 7);
        var request = new SimulationRecommender.Request(user, mr, PaymentTerms.lumpSum(AMOUNT),
                "OFFLINE", merchant, category, date, date);
        var api = simulation.recommend(request).recommendation();
        var facts = merchant == null ? Map.<String, Boolean>of()
                : com.yamu.be.payment.recommend.PosPurchaseFacts.forCategory(category);
        var transaction = BasicPurchaseSimulation.apply(new RecommendationInput.Transaction(AMOUNT,
                RecommendationInput.Plan.LUMP_SUM, 1, Instant.parse("2026-11-07T03:00:00Z"),
                merchant, category, facts), "OFFLINE", null);
        assertThat(transaction.facts()).doesNotContainKey("RULE:UTILITY_PAYMENT");
        var result = engine.recommendSimulation(user, transaction, new CalculationOptions(merchant == null)).recommendation();
        var evaluation = result.evaluatedCards().stream().filter(c -> c.userCardId() == mr).findFirst().orElseThrow();
        var calculation = evaluation.calculation();
        System.out.println("UTILITY_REPRO " + brand + " status=" + evaluation.status() + " reason=" + evaluation.reason()
                + " net=" + calculation.netBenefit() + " problems=" + calculation.problems()
                + " missing=" + calculation.missingInformation() + " excluded=" + calculation.excludedBenefits()
                + " apiMissing=" + api.missingInformation());
        if (brand.equals("KT")) {
            assertThat(calculation.complete()).isFalse();
            assertThat(api.benefit()).isNull();
            assertThat(calculation.problems()).extracting(CardBenefitCalculator.Problem::code).containsExactly("FACT_MISSING");
            assertThat(calculation.missingInformation()).extracting(CardBenefitCalculator.MissingInformation::field)
                    .containsExactly("facts.RULE:UTILITY_PAYMENT");
            long utility = jdbc.queryForObject("SELECT b.card_benefit_id FROM card_benefit b JOIN card_product p USING(card_product_id) "
                    + "WHERE p.product_name='신한카드 Mr.Life' AND b.benefit_code='UTILITY' AND b.policy_version=1", Long.class);
            assertThat(calculation.problems()).extracting(CardBenefitCalculator.Problem::benefitId).containsExactly(utility);
        } else {
            assertThat(calculation.complete()).isTrue();
            assertThat(calculation.problems()).isEmpty();
            assertThat(calculation.missingInformation()).extracting(CardBenefitCalculator.MissingInformation::field)
                    .doesNotContain("facts.RULE:UTILITY_PAYMENT");
            assertThat(api.benefit()).isNotNull();
            assertThat(calculation.netBenefit()).isEqualByComparingTo(brand.equals("EMART") ? "5000" : "0");
            assertThat(api.benefit().netBenefit()).isEqualByComparingTo(calculation.netBenefit());
        }
    }
    private SimulationRecommender.Request request(long user, Long cardId, Long merchant) {
        return new SimulationRecommender.Request(user, cardId, PaymentTerms.lumpSum(AMOUNT),
                "OFFLINE", merchant, "HYPERMARKET", TODAY, TODAY);
    }
    /** 전월 실적 50만 원·이번 달 한도 사용 0인 보유 카드(POS 쪽 PosPurchaseFactsBenefitDatabaseTest와 같은 조건). */
    private long card(long user, String productName) {
        long product = jdbc.queryForObject("SELECT card_product_id FROM card_product WHERE product_name = ?", Long.class,
                productName);
        var fixtures = new OwnershipFixtures(jdbc);
        long id = fixtures.card(user, product, fixtures.account(user), "ACTIVE");
        jdbc.update("UPDATE user_card SET payment_day = 15 WHERE user_card_id = ?", id);
        jdbc.update("UPDATE app_user SET ssafy_user_key = ? WHERE user_id = ?",
                cipher.encrypt("simulation-facts-user-" + user, CipherField.APP_USER_SSAFY_USER_KEY), user);
        jdbc.update("UPDATE linked_account SET account_no_enc = ? WHERE linked_account_id = "
                        + "(SELECT linked_account_id FROM user_card WHERE user_card_id = ?)",
                cipher.encrypt("simulation-facts-account-" + id, CipherField.LINKED_ACCOUNT_NO), id);
        jdbc.update("INSERT INTO card_monthly_performance(user_card_id, performance_month, recognized_amount, created_at, updated_at) "
                + "VALUES (?, '2026-10-01', 500000, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)), "
                + "(?, '2026-11-01', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))", id, id);
        jdbc.update("INSERT INTO monthly_limit_usage(user_card_id, card_product_id, limit_group_id, usage_month, used_value, created_at, updated_at) "
                + "SELECT ?, card_product_id, limit_group_id, '2026-11-01', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6) "
                + "FROM limit_group WHERE card_product_id = ? AND period_type = 'MONTHLY'", id, product);
        return id;
    }

}
