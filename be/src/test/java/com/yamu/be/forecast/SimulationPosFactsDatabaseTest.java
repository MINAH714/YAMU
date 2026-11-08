package com.yamu.be.forecast;

import static org.assertj.core.api.Assertions.assertThat;

import com.yamu.be.global.crypto.CipherField;
import com.yamu.be.global.crypto.PersonalDataCipher;
import com.yamu.be.payment.PaymentTerms;
import com.yamu.be.payment.recommend.CardRecommender;
import com.yamu.be.payment.recommend.RecommendationRequest;
import com.yamu.be.payment.recommend.RecommendationResult;
import com.yamu.be.support.MySqlTestSupport;
import com.yamu.be.support.OwnershipFixtures;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
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

/**
 * 가상 구매 추천이 같은 가맹점에서 POS 결제 추천과 같은 거래 사실({@code PosPurchaseFacts})을 써서 같은 카드·혜택을 내는지 실제 시드
 * 카탈로그로 확인한다(2026-11-08 결정). 시드 LOCA LIKIT Play의 주유 60% 할인은 제외 조건 {@code TRANSACTION_KIND:LPG_STATION}을
 * 보므로, 사실이 없으면 "자료 부족"이고 있으면 10,000원 × 60% = 6,000원이다.
 *
 * <p>SK에너지 한 곳의 사례다. 모든 브랜드에서 POS와 결과가 같다는 증명은 아니다: 가상 구매는 구매 시각(낮 12시)과 시설(일반 매장)을
 * 가정하고 POS는 실제 요청 시각·단말 시설을 쓴다. 여기서는 POS도 낮 12시·일반 매장이라 두 입력이 같다.
 *
 * <p>테스트 트랜잭션으로 감싸지 않는다. 추천 엔진이 DB 트랜잭션 안에서 부르면 거절하기 때문이다.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = "spring.application.name=simulation-pos-facts")
@Import(SimulationPosFactsDatabaseTest.TimeConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SimulationPosFactsDatabaseTest extends MySqlTestSupport {

    /** 2026-11-03 12:00 KST. 가상 구매는 구매일 낮 12시로 가정하므로 POS 요청 시각과 같다. */
    static final Instant NOW = Instant.parse("2026-11-03T03:00:00Z");
    static final LocalDate TODAY = LocalDate.of(2026, 11, 3);
    static final long AMOUNT = 10_000L;

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
    void 주유소_브랜드를_고른_가상_구매는_POS_결제와_같이_LIKIT_Play_주유_60퍼센트_6000원을_1순위로_낸다() {
        long user = new OwnershipFixtures(jdbc).user();
        long likit = card(user, "LOCA LIKIT Play");
        long merchant = skEnergy();

        SimulationRecommender.Recommendation simulated = simulation.recommend(new SimulationRecommender.Request(user, null,
                PaymentTerms.lumpSum(AMOUNT), "OFFLINE", merchant, "GAS_STATION", TODAY, TODAY)).recommendation();
        RecommendationResult paid = pos.recommend(RecommendationRequest.posPurchase(100L, user, PaymentTerms.lumpSum(AMOUNT),
                new RecommendationRequest.MerchantContext(merchant, "GAS_STATION", "OFFLINE", "GENERAL"),
                LocalDateTime.ofInstant(NOW, ZoneOffset.UTC)));

        var top = paid.topCandidate().orElseThrow();
        assertThat(top.card().userCardId()).isEqualTo(likit);
        assertThat(top.netBenefit()).isEqualByComparingTo(new BigDecimal("6000"));
        assertThat(simulated.userCardId()).isEqualTo(likit);
        assertThat(simulated.benefit()).isNotNull();
        assertThat(simulated.benefit().netBenefit()).isEqualByComparingTo(top.netBenefit());
    }

    @Test
    void 업종만_고른_가상_구매는_거래_사실이_없어_LIKIT_Play_주유_할인을_계산하지_않는다() {
        long user = new OwnershipFixtures(jdbc).user();
        long likit = card(user, "LOCA LIKIT Play");

        SimulationRecommender.Recommendation simulated = simulation.recommend(new SimulationRecommender.Request(user, likit,
                PaymentTerms.lumpSum(AMOUNT), "OFFLINE", null, "GAS_STATION", TODAY, TODAY)).recommendation();

        assertThat(simulated.userCardId()).isEqualTo(likit);
        assertThat(simulated.benefit()).isNull();
        assertThat(simulated.missingInformation()).isNotEmpty();
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

    private long skEnergy() {
        return jdbc.queryForObject("SELECT merchant_id FROM merchant WHERE brand_code = 'SK_ENERGY'", Long.class);
    }
}
