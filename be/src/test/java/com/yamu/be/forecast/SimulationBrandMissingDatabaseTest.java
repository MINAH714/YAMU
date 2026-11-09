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
@Import(SimulationBrandMissingDatabaseTest.TimeConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SimulationBrandMissingDatabaseTest extends MySqlTestSupport {

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

    @Test
    void engineKeepsExclusionEvidenceAndStrictModeStillCannotCalculateBothCards() {
        long user = new OwnershipFixtures(jdbc).user();
        card(user, "신한카드 Mr.Life");
        card(user, "KB국민 노리 체크");
        var transaction = BasicPurchaseSimulation.apply(new RecommendationInput.Transaction(AMOUNT,
                RecommendationInput.Plan.LUMP_SUM, 1, NOW, null, "HYPERMARKET", Map.of()), "OFFLINE", null);
        assertThat(transaction.facts()).doesNotContainKeys("RULE:UTILITY_PAYMENT", "RULE:OFFICIAL_MERCHANT_ROUTE");
        var strict = engine.recommendSimulation(user, transaction, CalculationOptions.DEFAULT).recommendation();
        assertThat(strict.selectedUserCardId()).isNull();
        assertThat(strict.evaluatedCards()).hasSize(2).allSatisfy(card -> {
            assertThat(card.calculation().netBenefit()).isNull();
            assertThat(card.calculation().problems()).extracting(CardBenefitCalculator.Problem::code)
                    .contains("BRAND_MISSING", "FACT_MISSING");
            assertThat(card.calculation().excludedBenefits()).isEmpty();
        });
        var scoped = engine.recommendSimulation(user, transaction, new CalculationOptions(true)).recommendation();
        assertThat(scoped.selectedUserCardId()).isNotNull();
        assertThat(scoped.evaluatedCards()).hasSize(2).allSatisfy(card -> {
            var calculation = card.calculation();
            assertThat(calculation.complete()).isTrue();
            assertThat(calculation.netBenefit()).isZero();
            assertThat(calculation.problems()).isEmpty();
            assertThat(calculation.excludedBenefits()).isNotEmpty().allSatisfy(excluded -> {
                assertThat(excluded.reasonCode()).isEqualTo("BRAND_MISSING");
                assertThat(calculation.benefits()).extracting(CardBenefitCalculator.BenefitValue::benefitId)
                        .doesNotContain(excluded.benefitId());
                assertThat(calculation.missingInformation()).contains(new CardBenefitCalculator.MissingInformation(
                        excluded.benefitId(), "BRAND_MISSING", "merchantId"));
            });
            assertThat(calculation.missingInformation()).extracting(CardBenefitCalculator.MissingInformation::field)
                    .doesNotContain("facts.RULE:UTILITY_PAYMENT", "facts.RULE:OFFICIAL_MERCHANT_ROUTE");
            assertThat(calculation.simulationRules()).flatExtracting(rule -> rule.appliedFields())
                    .doesNotContain("RULE:UTILITY_PAYMENT", "RULE:OFFICIAL_MERCHANT_ROUTE");
        });
    }

    @Test
    void emartSimulationAndPosKeepSameRecommendation() {
        long user = new OwnershipFixtures(jdbc).user();
        card(user, "신한카드 Mr.Life");
        card(user, "KB국민 노리 체크");
        long merchant = jdbc.queryForObject("SELECT merchant_id FROM merchant WHERE brand_code = 'EMART'", Long.class);
        var paid = pos.recommend(RecommendationRequest.posPurchase(100L, user, PaymentTerms.lumpSum(AMOUNT),
                new RecommendationRequest.MerchantContext(merchant, "HYPERMARKET", "OFFLINE", "GENERAL"),
                LocalDateTime.ofInstant(NOW, ZoneOffset.UTC)));
        var automatic = simulation.recommend(request(user, null, merchant)).recommendation();
        assertThat(automatic.benefit()).isNotNull();
        assertThat(automatic.userCardId()).isEqualTo(paid.topCandidate().orElseThrow().card().userCardId());
        assertThat(automatic.benefit().netBenefit()).isEqualByComparingTo(paid.topCandidate().orElseThrow().netBenefit());
        assertThat(paid.candidates()).hasSize(2).allSatisfy(candidate -> {
            var direct = simulation.recommend(request(user, candidate.card().userCardId(), merchant)).recommendation();
            assertThat(direct.benefit()).isNotNull();
            assertThat(direct.benefit().netBenefit()).isEqualByComparingTo(candidate.netBenefit());
            assertThat(direct.assumptions()).extracting(SimulationRecommender.Assumption::id)
                    .doesNotContain("YAMU_BRAND_NOT_SELECTED");
        });
    }

    /** 승인된 모의 대상: 마트 3종 + 기업형 슈퍼마켓 3종. */
    @Test
    void saturdayNamedBrandsCalculateAndWeekdayStillExcludesMartDiscount() {
        long user = new OwnershipFixtures(jdbc).user();
        long mrLife = card(user, "신한카드 Mr.Life");
        card(user, "KB국민 노리 체크");
        LocalDate saturday = LocalDate.of(2026, 11, 7);
        var absent = simulation.recommend(new SimulationRecommender.Request(user, mrLife,
                PaymentTerms.lumpSum(AMOUNT), "OFFLINE", null, "HYPERMARKET", saturday, saturday)).recommendation();
        assertThat(absent.benefit().netBenefit()).isZero();
        assertThat(absent.assumptions()).extracting(SimulationRecommender.Assumption::id)
                .contains("YAMU_BRAND_NOT_SELECTED");
        for (String brand : List.of("EMART", "LOTTE_MART", "HOMEPLUS", "EMART_EVERYDAY", "LOTTE_SUPER", "HOMEPLUS_EXPRESS")) {
            long merchant = jdbc.queryForObject("SELECT merchant_id FROM merchant WHERE brand_code = ?", Long.class, brand);
            String category = jdbc.queryForObject("SELECT merchant_category_code FROM merchant WHERE merchant_id = ?", String.class, merchant);
            var selected = simulation.recommend(new SimulationRecommender.Request(user, mrLife,
                    PaymentTerms.lumpSum(AMOUNT), "OFFLINE", merchant, category, saturday, saturday)).recommendation();
            assertThat(selected.benefit()).as(brand).isNotNull();
            assertThat(selected.benefit().netBenefit()).as(brand).isEqualByComparingTo("5000");
            assertThat(selected.missingInformation()).extracting(SimulationRecommender.MissingInformation::field)
                    .doesNotContain("facts.RULE:MR_LIFE_MART_FAMILY");
            var automatic = simulation.recommend(new SimulationRecommender.Request(user, null,
                    PaymentTerms.lumpSum(AMOUNT), "OFFLINE", merchant, category, saturday, saturday)).recommendation();
            assertThat(automatic.userCardId()).as(brand).isEqualTo(mrLife);
            var weekday = simulation.recommend(new SimulationRecommender.Request(user, mrLife,
                    PaymentTerms.lumpSum(AMOUNT), "OFFLINE", merchant, category, TODAY, TODAY)).recommendation();
            assertThat(weekday.benefit().netBenefit()).as(brand).isZero();
            var paid = pos.recommend(RecommendationRequest.posPurchase(100L, user, PaymentTerms.lumpSum(AMOUNT),
                    new RecommendationRequest.MerchantContext(merchant, category, "OFFLINE", "GENERAL"),
                    LocalDateTime.of(2026, 11, 7, 3, 0)));
            assertThat(paid.topCandidate().orElseThrow().card().userCardId()).isEqualTo(mrLife);
            assertThat(paid.topCandidate().orElseThrow().netBenefit()).isEqualByComparingTo("5000");
        }
    }

    @Test
    void genericSupermarketAndUnselectedSupermarketDoNotReceiveMartDiscount() {
        long user = new OwnershipFixtures(jdbc).user();
        long mrLife = card(user, "신한카드 Mr.Life");
        jdbc.update("INSERT INTO merchant(brand_code, merchant_name, merchant_category_code, created_at, updated_at) "
                + "VALUES ('TEST_INDEPENDENT_SUPER', '일반 슈퍼', 'SUPERMARKET', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))");
        long merchant = jdbc.queryForObject("SELECT merchant_id FROM merchant WHERE brand_code = 'TEST_INDEPENDENT_SUPER'", Long.class);
        LocalDate saturday = LocalDate.of(2026, 11, 7);
        for (Long selected : new Long[] {null, merchant}) {
            var result = simulation.recommend(new SimulationRecommender.Request(user, mrLife,
                    PaymentTerms.lumpSum(AMOUNT), "OFFLINE", selected, "SUPERMARKET", saturday, saturday));
            assertThat(result.recommendation().benefit().netBenefit()).isZero();
        }
    }

    @Test
    void catalogRepairsLegacyTargetsAndCanBeAppliedTwice() throws Exception {
        long benefit = jdbc.queryForObject("SELECT b.card_benefit_id FROM card_benefit b JOIN card_product p USING(card_product_id) "
                + "WHERE p.product_name = '신한카드 Mr.Life' AND b.benefit_code = 'WEEKEND_MART' AND b.policy_version = 1", Long.class);
        jdbc.update("INSERT INTO benefit_target(card_benefit_id,target_type,target_value,is_excluded,created_at) "
                + "VALUES (?, 'CATEGORY', 'SUPERMARKET', FALSE, UTC_TIMESTAMP(6)), "
                + "(?, 'CONDITION', 'RULE:MR_LIFE_MART_FAMILY', FALSE, UTC_TIMESTAMP(6))", benefit, benefit);
        jdbc.update("INSERT INTO benefit_target_group(card_benefit_id,group_code,is_excluded,created_at) "
                + "VALUES (?, CONCAT('LEGACY_0_',LEFT(SHA2('CATEGORY:SUPERMARKET',256),40)), FALSE, UTC_TIMESTAMP(6))", benefit);
        jdbc.update("INSERT INTO benefit_target_group_member(benefit_target_group_id,benefit_target_id,card_benefit_id) "
                + "SELECT g.benefit_target_group_id,t.benefit_target_id,? FROM benefit_target_group g "
                + "JOIN benefit_target t ON t.card_benefit_id=g.card_benefit_id WHERE g.card_benefit_id=? "
                + "AND g.group_code=CONCAT('LEGACY_0_',LEFT(SHA2('CATEGORY:SUPERMARKET',256),40)) "
                + "AND t.target_type='CATEGORY' AND t.target_value='SUPERMARKET'", benefit, benefit);
        for (int attempt = 0; attempt < 2; attempt++) {
            seed();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM benefit_target WHERE card_benefit_id=? "
                    + "AND target_value IN ('SUPERMARKET','RULE:MR_LIFE_MART_FAMILY')", Integer.class, benefit)).isZero();
            assertThat(jdbc.queryForList("SELECT m.brand_code FROM benefit_target t JOIN merchant m USING(merchant_id) "
                    + "WHERE t.card_benefit_id=? AND t.target_type='BRAND'", String.class, benefit))
                    .containsExactlyInAnyOrder("EMART", "LOTTE_MART", "HOMEPLUS", "EMART_EVERYDAY", "LOTTE_SUPER", "HOMEPLUS_EXPRESS");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM benefit_target_group WHERE card_benefit_id=?", Integer.class, benefit))
                    .isEqualTo(6);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM benefit_target WHERE card_benefit_id=? "
                    + "AND target_type='CONDITION' AND target_value='TRANSACTION_KIND:GIFT_CERTIFICATE_PURCHASE' AND is_excluded=TRUE",
                    Integer.class, benefit)).isEqualTo(1);
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
