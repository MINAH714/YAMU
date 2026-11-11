package com.yamu.be.forecast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.yamu.be.forecast.LivingCostEstimator.Confidence;
import com.yamu.be.forecast.LivingCostEstimator.DataOrigin;
import com.yamu.be.forecast.LivingCostProjector.AccountProjection;
import com.yamu.be.support.MySqlTestSupport;
import com.yamu.be.support.OwnershipFixtures;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

/**
 * 신용카드 마감 전 사용 추정을 실제 MySQL의 credit_spending_prediction에 남기는 것을 확인한다(S15P31E103-405). 동시 저장을 보려고
 * 트랜잭션으로 감싸지 않고, 테스트마다 새 사용자·카드를 만들어 서로의 행을 보지 않는다.
 */
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = "spring.application.name=yamu-credit-spending-prediction-log-test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CreditSpendingPredictionLogTest extends MySqlTestSupport {
    private static final LocalDate DAY = LocalDate.of(2026, 11, 7);

    @Autowired JdbcTemplate jdbc;
    @Autowired CreditSpendingPredictionLog log;

    OwnershipFixtures ownership;
    long owner;
    long card;

    @BeforeEach
    void setUp() {
        ownership = new OwnershipFixtures(jdbc);
        owner = ownership.user();
        card = ownership.card(owner, ownership.product(), ownership.account(owner), "ACTIVE");
    }

    @Test
    void 카드별_예측값을_남은_일수와_함께_한_행씩_남긴다() {
        long other = ownership.card(owner, ownership.product(), ownership.account(owner), "ACTIVE");

        log.record(owner, DAY, List.of(
                prediction(card, 4, 40_000L, 10_000L, "ML_V1", Confidence.HIGH, DataOrigin.REAL),
                prediction(other, 23, 46_000L, 2_000L, "TSB", Confidence.LOW, DataOrigin.SEED)));

        assertThat(jdbc.queryForList("""
                SELECT user_card_id, user_id, predicted_on, horizon_days, method, model_version, projected_amount, daily_amount,
                    confidence, data_origin, created_at IS NOT NULL AS has_created_at
                FROM credit_spending_prediction WHERE user_id = ? ORDER BY user_card_id""", owner))
                .extracting(row -> tuple(((Number) row.get("user_card_id")).longValue(),
                        ((Number) row.get("user_id")).longValue(), row.get("predicted_on").toString(),
                        ((Number) row.get("horizon_days")).intValue(), row.get("method"), row.get("model_version"),
                        ((Number) row.get("projected_amount")).longValue(), ((Number) row.get("daily_amount")).longValue(),
                        row.get("confidence"), row.get("data_origin"), ((Number) row.get("has_created_at")).intValue()))
                .containsExactly(
                        tuple(card, owner, "2026-11-07", 4, "ML_V1", "credit-v1", 40_000L, 10_000L, "HIGH", "REAL", 1),
                        tuple(other, owner, "2026-11-07", 23, "TSB", null, 46_000L, 2_000L, "LOW", "SEED", 1));
    }

    @Test
    void 같은_날_같은_남은_일수는_첫_값만_남기고_남은_일수나_날짜가_다르면_따로_남긴다() {
        log.record(owner, DAY, List.of(prediction(4, 40_000L, 10_000L)));
        log.record(owner, DAY, List.of(prediction(4, 48_000L, 12_000L)));
        // 같은 날 결제일·마감 일수를 바꿔 이용기간 마감일이 달라진 경우
        log.record(owner, DAY, List.of(prediction(19, 38_000L, 2_000L)));
        log.record(owner, DAY.plusDays(1), List.of(prediction(3, 30_000L, 10_000L)));

        assertThat(rows()).containsExactly(
                tuple("2026-11-07", 4, 40_000L),
                tuple("2026-11-07", 19, 38_000L),
                tuple("2026-11-08", 3, 30_000L));
    }

    @Test
    void 이미_저장한_카드는_다시_쓰지_않아_자동_증가_번호를_쓰지_않는다() {
        log.record(owner, DAY, List.of(prediction(4, 40_000L, 10_000L)));
        long first = idOf(DAY, 4);
        for (int i = 0; i < 5; i++) {
            log.record(owner, DAY, List.of(prediction(4, 48_000L, 12_000L)));
        }

        log.record(owner, DAY, List.of(prediction(19, 38_000L, 2_000L)));

        assertThat(idOf(DAY, 19)).isEqualTo(first + 1);
    }

    @Test
    void 예측이_없으면_아무것도_하지_않는다() {
        log.record(owner, DAY, List.of());

        assertThat(rows()).isEmpty();
    }

    @Test
    void 동시에_저장해도_카드_날짜_남은_일수마다_한_행이다() throws Exception {
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                long amount = 10_000L * (i + 1);
                futures.add(pool.submit(() -> {
                    start.await();
                    log.record(owner, DAY, List.of(prediction(4, amount * 4, amount)));
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM credit_spending_prediction WHERE user_card_id = ? AND predicted_on = ?",
                Long.class, card, DAY)).isEqualTo(1L);
    }

    @Test
    void 저장이_실패해도_던지지_않고_금액_없이_예외_이름만_경고로_남긴다(CapturedOutput output) {
        // 다른 사용자의 카드는 소유 외래 키가 거절한다. 예보는 부르기 전에 소유를 확인하므로 실제로는 DB 장애 같은 경우다.
        long stranger = ownership.user();

        log.record(stranger, DAY, List.of(prediction(4, 1_234_567L, 308_642L)));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM credit_spending_prediction WHERE user_card_id = ?",
                Long.class, card)).isZero();
        assertThat(output.getOut()).contains("신용 사용 추정 이력을 저장하지 못했다")
                .contains("userCardIds=[" + card + "]")
                .contains("cause=DataIntegrityViolationException")
                .doesNotContain("1234567").doesNotContain("308642")
                .doesNotContain("fk_credit_prediction_card_owner").doesNotContain("credit_spending_prediction (");
    }

    @Test
    void 예측값이_계약을_어기면_숨기지_않고_던진다() {
        assertThatThrownBy(() -> log.record(owner, DAY, List.of(prediction(4, -1L, 0L))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> log.record(owner, DAY, List.of(new CreditSpendingEstimator.Prediction(card, 4,
                AccountProjection.insufficient(card), null)))).isInstanceOf(IllegalStateException.class);
        // AI면 모델 버전이 있어야 하고 TSB면 없어야 한다
        AccountProjection ai = new AccountProjection(card, 40_000L, 10_000L, "ML_V1", Confidence.HIGH, DataOrigin.REAL);
        AccountProjection tsb = new AccountProjection(card, 40_000L, 10_000L, "TSB", Confidence.HIGH, DataOrigin.REAL);
        assertThatThrownBy(() -> log.record(owner, DAY, List.of(new CreditSpendingEstimator.Prediction(card, 4, ai,
                null)))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> log.record(owner, DAY, List.of(new CreditSpendingEstimator.Prediction(card, 4, tsb,
                "credit-v1")))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> log.record(owner, DAY, List.of(prediction(0, 0L, 0L))))
                .isInstanceOf(IllegalStateException.class);

        assertThat(rows()).isEmpty();
    }

    private CreditSpendingEstimator.Prediction prediction(int remainingDays, long projectedAmount, long dailyAmount) {
        return prediction(card, remainingDays, projectedAmount, dailyAmount, "TSB", Confidence.MEDIUM, DataOrigin.REAL);
    }

    private static CreditSpendingEstimator.Prediction prediction(long userCardId, int remainingDays,
            long projectedAmount, long dailyAmount, String method, Confidence confidence, DataOrigin origin) {
        return new CreditSpendingEstimator.Prediction(userCardId, remainingDays,
                new AccountProjection(userCardId, projectedAmount, dailyAmount, method, confidence, origin),
                "ML_V1".equals(method) ? "credit-v1" : null);
    }

    private long idOf(LocalDate day, int horizonDays) {
        return jdbc.queryForObject("""
                SELECT credit_spending_prediction_id FROM credit_spending_prediction
                WHERE user_card_id = ? AND predicted_on = ? AND horizon_days = ?""",
                Long.class, card, day, horizonDays);
    }

    private List<org.assertj.core.groups.Tuple> rows() {
        return jdbc.query("""
                SELECT predicted_on, horizon_days, projected_amount FROM credit_spending_prediction
                WHERE user_card_id = ? ORDER BY predicted_on, horizon_days""",
                (rs, i) -> tuple(rs.getString("predicted_on"), rs.getInt("horizon_days"), rs.getLong("projected_amount")),
                card);
    }
}
