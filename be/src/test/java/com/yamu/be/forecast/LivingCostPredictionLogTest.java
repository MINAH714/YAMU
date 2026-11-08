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
 * 생활비 예측 이력을 실제 MySQL의 living_cost_prediction에 남기는 것을 확인한다. 동시 저장을 보려고 트랜잭션으로 감싸지 않고,
 * 테스트마다 새 사용자·계좌를 만들어 서로의 행을 보지 않는다.
 */
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = "spring.application.name=yamu-living-cost-prediction-log-test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LivingCostPredictionLogTest extends MySqlTestSupport {
    private static final LocalDate DAY = LocalDate.of(2026, 11, 7);

    @Autowired JdbcTemplate jdbc;
    @Autowired LivingCostPredictionLog log;

    OwnershipFixtures ownership;
    long owner;
    long account;

    @BeforeEach
    void setUp() {
        ownership = new OwnershipFixtures(jdbc);
        owner = ownership.user();
        account = ownership.account(owner);
    }

    @Test
    void 계좌별_예측값을_한_행씩_남긴다() {
        long other = ownership.account(owner);

        log.record(owner, DAY, 14, List.of(
                new AccountProjection(account, 70_000L, 5_000L, "ML_V1", Confidence.HIGH, DataOrigin.REAL),
                new AccountProjection(other, 28_000L, 2_000L, "TSB", Confidence.LOW, DataOrigin.SEED)));

        assertThat(jdbc.queryForList("""
                SELECT linked_account_id, user_id, predicted_on, horizon_days, method, projected_amount, daily_amount,
                    confidence, data_origin, created_at IS NOT NULL AS has_created_at
                FROM living_cost_prediction WHERE user_id = ? ORDER BY linked_account_id""", owner))
                .extracting(row -> tuple(((Number) row.get("linked_account_id")).longValue(),
                        ((Number) row.get("user_id")).longValue(), row.get("predicted_on").toString(),
                        ((Number) row.get("horizon_days")).intValue(), row.get("method"),
                        ((Number) row.get("projected_amount")).longValue(), ((Number) row.get("daily_amount")).longValue(),
                        row.get("confidence"), row.get("data_origin"), ((Number) row.get("has_created_at")).intValue()))
                .containsExactly(
                        tuple(account, owner, "2026-11-07", 14, "ML_V1", 70_000L, 5_000L, "HIGH", "REAL", 1),
                        tuple(other, owner, "2026-11-07", 14, "TSB", 28_000L, 2_000L, "LOW", "SEED", 1));
    }

    @Test
    void 같은_날_같은_기간은_첫_값만_남기고_기간이나_날짜가_다르면_따로_남긴다() {
        log.record(owner, DAY, 14, List.of(projection(70_000L, 5_000L)));
        log.record(owner, DAY, 14, List.of(projection(84_000L, 6_000L)));
        log.record(owner, DAY, 30, List.of(projection(150_000L, 5_000L)));
        log.record(owner, DAY.plusDays(1), 14, List.of(projection(56_000L, 4_000L)));

        assertThat(rows()).containsExactly(
                tuple("2026-11-07", 14, 70_000L),
                tuple("2026-11-07", 30, 150_000L),
                tuple("2026-11-08", 14, 56_000L));
    }

    @Test
    void 이미_저장한_계좌는_다시_쓰지_않아_자동_증가_번호를_쓰지_않는다() {
        log.record(owner, DAY, 14, List.of(projection(70_000L, 5_000L)));
        long first = idOf(DAY, 14);
        for (int i = 0; i < 5; i++) {
            log.record(owner, DAY, 14, List.of(projection(84_000L, 6_000L)));
        }

        log.record(owner, DAY, 30, List.of(projection(150_000L, 5_000L)));

        // 중복이어도 INSERT를 보내면 MySQL 8 기본 설정(innodb_autoinc_lock_mode=2)에서 번호를 하나씩 써 first + 6이 된다
        assertThat(idOf(DAY, 30)).isEqualTo(first + 1);
    }

    @Test
    void 일부_계좌만_저장돼_있으면_빠진_계좌만_넣는다() {
        long other = ownership.account(owner);
        log.record(owner, DAY, 14, List.of(projection(70_000L, 5_000L)));

        log.record(owner, DAY, 14, List.of(projection(84_000L, 6_000L),
                new AccountProjection(other, 28_000L, 2_000L, "TSB", Confidence.LOW, DataOrigin.REAL)));

        assertThat(jdbc.queryForList("""
                SELECT linked_account_id, projected_amount FROM living_cost_prediction
                WHERE user_id = ? ORDER BY linked_account_id""", owner))
                .extracting(row -> tuple(((Number) row.get("linked_account_id")).longValue(),
                        ((Number) row.get("projected_amount")).longValue()))
                .containsExactly(tuple(account, 70_000L), tuple(other, 28_000L));
    }

    @Test
    void 자료_부족_계좌와_기간_0일은_남기지_않는다() {
        long empty = ownership.account(owner);

        log.record(owner, DAY, 14, List.of(projection(70_000L, 5_000L), AccountProjection.insufficient(empty)));
        log.record(owner, DAY, 0, List.of(projection(0L, 0L)));

        assertThat(rows()).containsExactly(tuple("2026-11-07", 14, 70_000L));
    }

    @Test
    void 동시에_저장해도_계좌_날짜_기간마다_한_행이다() throws Exception {
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                long amount = 10_000L * (i + 1);
                futures.add(pool.submit(() -> {
                    start.await();
                    log.record(owner, DAY, 7, List.of(projection(amount * 7, amount)));
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
                "SELECT COUNT(*) FROM living_cost_prediction WHERE user_id = ? AND predicted_on = ? AND horizon_days = 7",
                Long.class, owner, DAY)).isEqualTo(1L);
    }

    @Test
    void 저장이_실패해도_던지지_않고_금액_없이_예외_이름만_경고로_남긴다(CapturedOutput output) {
        // 다른 사용자의 계좌는 소유 외래 키가 거절한다. 예보는 부르기 전에 소유를 확인하므로 실제로는 DB 장애 같은 경우다.
        long stranger = ownership.user();

        log.record(stranger, DAY, 14, List.of(projection(1_234_567L, 88_183L)));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM living_cost_prediction WHERE linked_account_id = ?",
                Long.class, account)).isZero();
        assertThat(output.getOut()).contains("생활비 예측 이력을 저장하지 못했다")
                .contains("accountIds=[" + account + "]")
                .contains("cause=DataIntegrityViolationException")
                .doesNotContain("1234567").doesNotContain("88183")
                // 다른 로그처럼 예외 메시지(SQL·제약 이름)와 스택은 남기지 않는다
                .doesNotContain("fk_prediction_account_owner").doesNotContain("living_cost_prediction (");
    }

    @Test
    void 예측값이_계약을_어기면_숨기지_않고_던진다() {
        assertThatThrownBy(() -> log.record(owner, DAY, 14, List.of(projection(-1L, 0L))))
                .isInstanceOf(IllegalStateException.class);

        assertThat(rows()).isEmpty();
    }

    private AccountProjection projection(long projectedAmount, long dailyAmount) {
        return new AccountProjection(account, projectedAmount, dailyAmount, "TSB", Confidence.MEDIUM, DataOrigin.REAL);
    }

    private long idOf(LocalDate day, int horizonDays) {
        return jdbc.queryForObject("""
                SELECT living_cost_prediction_id FROM living_cost_prediction
                WHERE linked_account_id = ? AND predicted_on = ? AND horizon_days = ?""",
                Long.class, account, day, horizonDays);
    }

    private List<org.assertj.core.groups.Tuple> rows() {
        return jdbc.query("""
                SELECT predicted_on, horizon_days, projected_amount FROM living_cost_prediction
                WHERE linked_account_id = ? ORDER BY predicted_on, horizon_days""",
                (rs, i) -> tuple(rs.getString("predicted_on"), rs.getInt("horizon_days"), rs.getLong("projected_amount")),
                account);
    }
}
