package com.yamu.be.payment.recommend;

import static org.assertj.core.api.Assertions.assertThat;

import com.yamu.be.global.crypto.CipherField;
import com.yamu.be.global.crypto.PersonalDataCipher;
import com.yamu.be.payment.PaymentPlan;
import com.yamu.be.payment.PaymentTerms;
import com.yamu.be.recommendation.RecommendationCommand;
import com.yamu.be.recommendation.RecommendationInput;
import com.yamu.be.support.MySqlTestSupport;
import com.yamu.be.support.OwnershipFixtures;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
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
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;

/**
 * POS 거래 사실({@link PosPurchaseFacts})이 실적 인정뿐 아니라 혜택 계산도 바꾼다는 것을 실제 시드 카탈로그로 고정한다(Task 5.2.11,
 * !163 리뷰). 시드의 혜택 제외 조건({@code benefit_target} CONDITION) 일부가 같은 키(예: {@code TRANSACTION_KIND:LPG_STATION})를
 * 보므로, 키가 비어 있으면 그 혜택은 "자료 부족"으로 비교에서 빠지고 키를 넘기면 계산된다.
 *
 * <p>이 테스트가 깨지면 PosPurchaseFacts의 키를 빼거나 더해 추천 결과가 바뀐 것이다. 의도한 변화인지 BE3와 확인한다.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {"spring.application.name=pos-facts-benefit",
        "yamu.pos.terminals[0].brand-code=SK_ENERGY", "yamu.pos.terminals[0].pos-code=POS-TEST-FUEL",
        "yamu.pos.terminals[0].channel=OFFLINE", "yamu.pos.terminals[0].facility-type=GENERAL"})
@Import(PosPurchaseFactsBenefitDatabaseTest.TimeConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PosPurchaseFactsBenefitDatabaseTest extends MySqlTestSupport {

    @Container
    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    /** 2026-11-03 12:00 KST */
    static final Instant NOW = Instant.parse("2026-11-03T03:00:00Z");

    @TestConfiguration
    static class TimeConfig {
        @Bean
        @Primary
        Clock posFactsClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired DataSource source;
    @Autowired JdbcTemplate jdbc;
    @Autowired CardRecommender recommender;
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
    void LIKIT_Play_주유_할인은_POS_사실이_없으면_비교에서_빠지고_넘기면_60퍼센트로_계산된다() {
        long user = new OwnershipFixtures(jdbc).user();
        long likit = card(user, "LOCA LIKIT Play");

        // 사실 없음(310 이전): FUEL_60의 제외 조건 TRANSACTION_KIND:LPG_STATION을 모른다 → 자료 부족
        RecommendationResult before = recommender.recommend(request(user, likit, Map.of()));
        assertThat(before.candidates()).isEmpty();
        assertThat(before.uncomparedCards()).extracting(c -> c.card().userCardId()).containsExactly(likit);

        // POS 사실(310): LPG 충전소가 아님이 확정 → 10,000원 × 60% = 6,000원 결제일 할인
        RecommendationResult after = recommender.recommend(request(user, likit, PosPurchaseFacts.verified()));
        var top = after.topCandidate().orElseThrow();
        assertThat(top.card().userCardId()).isEqualTo(likit);
        assertThat(top.netBenefit()).isEqualByComparingTo(new BigDecimal("6000"));
    }

    @Test
    void POS_결제_요청은_PosPurchaseFacts를_그대로_싣는다() {
        var request = RecommendationRequest.posPurchase(null, 1L, PaymentTerms.lumpSum(10_000), fuel(),
                LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        assertThat(request.verifiedFacts()).isEqualTo(PosPurchaseFacts.verified());
    }

    /** 전월 실적 50만 원·이번 달 한도 사용 0인 보유 카드. */
    private long card(long user, String productName) {
        long product = jdbc.queryForObject("SELECT card_product_id FROM card_product WHERE product_name = ?", Long.class,
                productName);
        var fixtures = new OwnershipFixtures(jdbc);
        long id = fixtures.card(user, product, fixtures.account(user), "ACTIVE");
        jdbc.update("UPDATE user_card SET payment_day = 15 WHERE user_card_id = ?", id);
        jdbc.update("UPDATE app_user SET ssafy_user_key = ? WHERE user_id = ?",
                cipher.encrypt("pos-facts-user", CipherField.APP_USER_SSAFY_USER_KEY), user);
        jdbc.update("UPDATE linked_account SET account_no_enc = ? WHERE linked_account_id = "
                        + "(SELECT linked_account_id FROM user_card WHERE user_card_id = ?)",
                cipher.encrypt("pos-facts-account-" + id, CipherField.LINKED_ACCOUNT_NO), id);
        jdbc.update("INSERT INTO card_monthly_performance(user_card_id, performance_month, recognized_amount, created_at, updated_at) "
                + "VALUES (?, '2026-10-01', 500000, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)), "
                + "(?, '2026-11-01', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))", id, id);
        jdbc.update("INSERT INTO monthly_limit_usage(user_card_id, card_product_id, limit_group_id, usage_month, used_value, created_at, updated_at) "
                + "SELECT ?, card_product_id, limit_group_id, '2026-11-01', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6) "
                + "FROM limit_group WHERE card_product_id = ? AND period_type = 'MONTHLY'", id, product);
        return id;
    }

    private RecommendationRequest.MerchantContext fuel() {
        var merchant = jdbc.queryForMap("SELECT merchant_id, merchant_category_code FROM merchant WHERE brand_code = 'SK_ENERGY'");
        return new RecommendationRequest.MerchantContext(((Number) merchant.get("merchant_id")).longValue(),
                (String) merchant.get("merchant_category_code"), "OFFLINE", "GENERAL");
    }

    /** 10,000원 일시불·오프라인 일반 매장. 결제 가능 판정은 고정(이 테스트는 혜택 계산만 본다). */
    private RecommendationRequest request(long user, long card, Map<String, Boolean> facts) {
        var availability = Map.of(card,
                new RecommendationCommand.PaymentAvailability(RecommendationInput.Availability.AVAILABLE, null));
        return new RecommendationRequest(100L, user, new PaymentTerms(10_000, PaymentPlan.LUMP_SUM, null), fuel(),
                LocalDateTime.ofInstant(NOW, ZoneOffset.UTC), null, availability, facts);
    }
}
