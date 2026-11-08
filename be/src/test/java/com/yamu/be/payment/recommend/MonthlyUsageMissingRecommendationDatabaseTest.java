package com.yamu.be.payment.recommend;

import static org.assertj.core.api.Assertions.assertThat;

import com.yamu.be.global.crypto.CipherField;
import com.yamu.be.global.crypto.PersonalDataCipher;
import com.yamu.be.payment.PaymentPlan;
import com.yamu.be.payment.PaymentTerms;
import com.yamu.be.recommendation.RecommendationCommand;
import com.yamu.be.recommendation.RecommendationDataIssue;
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
 * 그 달 월 혜택 사용량 행이 없는 카드도 월 한도 혜택을 비교한다(S15P31E103-324, !167 리뷰). 앱에서 등록한 카드는 사용량 행이 없고,
 * 행은 첫 혜택 사용 승인 때 결제 원장이 만든다. 324 전에는 이런 카드가 LIMIT_USAGE_MISSING으로 비교에서 빠졌다.
 *
 * <p>실제 시드 카탈로그의 신한카드 Mr.Life(월 한도 그룹 있음)를 사용량 행 없이, 전월 실적만 넣고 추천해 최종 후보까지 본다.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {"spring.application.name=monthly-usage-missing",
        "yamu.pos.terminals[0].brand-code=GS25", "yamu.pos.terminals[0].pos-code=POS-TEST-CVS",
        "yamu.pos.terminals[0].channel=OFFLINE", "yamu.pos.terminals[0].facility-type=GENERAL"})
@Import(MonthlyUsageMissingRecommendationDatabaseTest.TimeConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MonthlyUsageMissingRecommendationDatabaseTest extends MySqlTestSupport {

    @Container
    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    /** 2026-11-03(화) 12:00 KST */
    static final Instant NOW = Instant.parse("2026-11-03T03:00:00Z");

    @TestConfiguration
    static class TimeConfig {
        @Bean
        @Primary
        Clock monthlyUsageClock() {
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
    void 이번_달_사용량_행이_없는_카드도_월_한도_혜택으로_비교되어_1순위가_된다() {
        long product = jdbc.queryForObject("SELECT card_product_id FROM card_product WHERE product_name = '신한카드 Mr.Life'",
                Long.class);
        // 전제: 이 상품의 혜택에 월 한도 그룹이 연결돼 있다(행이 없으면 324 전에는 미확인이던 경우)
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM benefit_limit_link l JOIN limit_group g ON g.limit_group_id = l.limit_group_id "
                + "WHERE l.card_product_id = ? AND g.period_type = 'MONTHLY'", Long.class, product)).isPositive();

        long user = new OwnershipFixtures(jdbc).user();
        long card = cardWithoutMonthlyUsage(user, product);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM monthly_limit_usage WHERE user_card_id = ?", Long.class, card))
                .isZero();

        RecommendationResult result = recommender.recommend(request(user, card));

        var top = result.topCandidate().orElseThrow();
        assertThat(top.card().userCardId()).isEqualTo(card);
        assertThat(top.netBenefit()).isEqualByComparingTo(new BigDecimal("1000"));
        assertThat(result.uncomparedCards()).isEmpty();
        assertThat(result.issues()).extracting(RecommendationDataIssue::code).doesNotContain("LIMIT_USAGE_MISSING");
    }

    /** 전월 실적 40만 원만 있고 월 혜택 사용량 행은 없는 보유 카드(앱에서 등록한 카드와 같은 상태). */
    private long cardWithoutMonthlyUsage(long user, long product) {
        var fixtures = new OwnershipFixtures(jdbc);
        long id = fixtures.card(user, product, fixtures.account(user), "ACTIVE");
        jdbc.update("UPDATE user_card SET payment_day = 15 WHERE user_card_id = ?", id);
        jdbc.update("UPDATE app_user SET ssafy_user_key = ? WHERE user_id = ?",
                cipher.encrypt("monthly-usage-user", CipherField.APP_USER_SSAFY_USER_KEY), user);
        jdbc.update("UPDATE linked_account SET account_no_enc = ? WHERE linked_account_id = "
                        + "(SELECT linked_account_id FROM user_card WHERE user_card_id = ?)",
                cipher.encrypt("monthly-usage-account-" + id, CipherField.LINKED_ACCOUNT_NO), id);
        jdbc.update("INSERT INTO card_monthly_performance(user_card_id, performance_month, recognized_amount, created_at, updated_at) "
                + "VALUES (?, '2026-10-01', 400000, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))", id);
        return id;
    }

    /** GS25 10,000원 일시불·오프라인 일반 매장. 결제 가능 판정은 고정(이 테스트는 한도 사용량 읽기만 본다). */
    private RecommendationRequest request(long user, long card) {
        var merchant = jdbc.queryForMap("SELECT merchant_id, merchant_category_code FROM merchant WHERE brand_code = 'GS25'");
        var availability = Map.of(card,
                new RecommendationCommand.PaymentAvailability(RecommendationInput.Availability.AVAILABLE, null));
        return new RecommendationRequest(100L, user, new PaymentTerms(10_000, PaymentPlan.LUMP_SUM, null),
                new RecommendationRequest.MerchantContext(((Number) merchant.get("merchant_id")).longValue(),
                        (String) merchant.get("merchant_category_code"), "OFFLINE", "GENERAL"),
                LocalDateTime.ofInstant(NOW, ZoneOffset.UTC), null, availability, Map.of());
    }
}
