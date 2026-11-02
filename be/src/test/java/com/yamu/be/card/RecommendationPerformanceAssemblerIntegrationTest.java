package com.yamu.be.card;

import static org.assertj.core.api.Assertions.assertThat;

import com.yamu.be.card.RecommendationPerformanceAssembler.Recognition;
import com.yamu.be.support.MySqlTestSupport;
import com.yamu.be.support.OwnershipFixtures;
import java.sql.Date;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

/** 실제 월 지정 SQL과 입력 조합을 격리 MySQL에서 검증한다. 운영 호출부의 시험은 아니다. */
@Transactional
@TestPropertySource(properties = "spring.application.name=yamu-recommendation-performance-test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RecommendationPerformanceAssemblerIntegrationTest extends MySqlTestSupport {
    @Autowired RecommendationPerformanceAssembler assembler;
    @Autowired JdbcTemplate jdbc;
    OwnershipFixtures fixtures;
    long user;
    long product;

    @BeforeEach
    void setUp() {
        fixtures = new OwnershipFixtures(jdbc);
        user = fixtures.user();
        product = fixtures.product();
    }

    @Test
    void usesOnlyRequestedRecognitionMonthWhenSameCardHasOtherMonthlyAggregates() {
        long card = fixtures.card(user, product, null, "ACTIVE");
        YearMonth september = YearMonth.of(2026, 9);
        YearMonth october = YearMonth.of(2026, 10);
        insert(card, september, 500_000);
        insert(card, october, 290_000);

        var progress = assembler.assemble(List.of(new Recognition(card, october, 300_000L, 10_000L))).get(card);

        assertThat(progress.period()).isEqualTo(october);
        assertThat(progress.beforeAmount()).isEqualTo(290_000L);
        assertThat(progress.newlyMeetsMinimum()).isTrue();
        assertThat(assembler.assemble(List.of(new Recognition(card, september, 300_000L, 10_000L)))
                .get(card).newlyMeetsMinimum()).isFalse();
    }

    @Test
    void preservesZeroAndDoesNotUseAnotherMonthsRowWhenRequestedRowIsMissing() {
        long zeroCard = fixtures.card(user, product, null, "ACTIVE");
        long missingCard = fixtures.card(user, product, null, "ACTIVE");
        YearMonth october = YearMonth.of(2026, 10);
        insert(zeroCard, october, 0);
        insert(missingCard, october.minusMonths(1), 290_000);

        var result = assembler.assemble(List.of(new Recognition(zeroCard, october, 300_000L, 10_000L),
                new Recognition(missingCard, october, 300_000L, 10_000L)));

        assertThat(result.get(zeroCard).beforeAmount()).isZero();
        assertThat(result.get(zeroCard).isKnown()).isTrue();
        assertThat(result.get(missingCard).beforeAmount()).isNull();
        assertThat(result.get(missingCard).isKnown()).isFalse();
    }

    private void insert(long card, YearMonth month, long amount) {
        jdbc.update("""
                INSERT INTO card_monthly_performance
                    (user_card_id, performance_month, recognized_amount, created_at, updated_at)
                VALUES (?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, card, Date.valueOf(month.atDay(1)), amount);
    }
}
