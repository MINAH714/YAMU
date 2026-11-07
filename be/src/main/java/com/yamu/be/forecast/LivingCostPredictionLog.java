package com.yamu.be.forecast;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 잔고예보·생활비 상세가 낸 계좌별 예상 생활비를 {@code living_cost_prediction}에 남긴다(S15P31E103-309).
 * 계좌·한국 날짜·기간(N)마다 그날 첫 값 한 행만 남기고, 같은 날 다시 조회해도 바꾸지 않는다. 동시 요청도 고유 키로 한 행이다.
 * 가상 구매는 남기지 않는다(부르는 쪽이 잔고예보·생활비 상세뿐이다).
 *
 * <p>이력은 예보 응답의 부산물이라 저장이 실패해도 예보는 그대로 준다. DB 오류만 경고 로그(사용자·계좌 번호와 원인, 금액 없음)로 남기고,
 * 예측값이 계약을 어기면(음수 등) 숨기지 않고 던진다.
 *
 * <p>{@code @Repository}가 아니라 {@code @Component}다. SQL 예외는 {@link NamedParameterJdbcTemplate}이 이미
 * {@link DataAccessException}으로 바꿔 준다.
 */
@Slf4j
@Component
class LivingCostPredictionLog {
    private static final String INSERT = """
            INSERT INTO living_cost_prediction (user_id, linked_account_id, predicted_on, horizon_days, method,
                projected_amount, daily_amount, confidence, data_origin, created_at)
            VALUES (:userId, :accountId, :predictedOn, :horizonDays, :method,
                :projectedAmount, :dailyAmount, :confidence, :dataOrigin, :createdAt)
            ON DUPLICATE KEY UPDATE living_cost_prediction_id = living_cost_prediction_id
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    LivingCostPredictionLog(NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * 예측값이 있는 계좌만 남긴다. 자료 부족(금액 null) 계좌와 기간 0일은 남기지 않는다.
     *
     * @param userId      계좌 소유 사용자(부르는 쪽이 이미 소유·연결을 확인했다)
     * @param predictedOn 예측한 한국 날짜(예보 시작일). 예측 기간은 다음 날부터 horizonDays일이다
     * @param horizonDays 예측 기간 일수 N
     * @param accounts    계좌별 예상 생활비
     */
    void record(long userId, LocalDate predictedOn, int horizonDays,
            Collection<LivingCostProjector.AccountProjection> accounts) {
        if (horizonDays < 1) {
            return;
        }
        LocalDateTime createdAt = LocalDateTime.now(clock.withZone(ZoneOffset.UTC));
        List<MapSqlParameterSource> rows = accounts.stream()
                .filter(account -> account.projectedAmount() != null)
                .map(account -> row(userId, predictedOn, horizonDays, account, createdAt))
                .toList();
        if (rows.isEmpty()) {
            return;
        }
        try {
            jdbc.batchUpdate(INSERT, rows.toArray(MapSqlParameterSource[]::new));
        } catch (DataAccessException e) {
            log.warn("생활비 예측 이력을 저장하지 못했다. 예보 응답은 그대로 준다. userId={}, accountIds={}, cause={}",
                    userId, rows.stream().map(row -> row.getValue("accountId")).toList(), e.getClass().getSimpleName(), e);
        }
    }

    private static MapSqlParameterSource row(long userId, LocalDate predictedOn, int horizonDays,
            LivingCostProjector.AccountProjection account, LocalDateTime createdAt) {
        // 자료가 있는 계좌는 금액·하루 값·방식·신뢰도·출처가 모두 있다(LivingCostProjector 계약). 어기면 조용히 넘기지 않는다.
        if (account.dailyAmount() == null || account.method() == null
                || account.projectedAmount() < 0 || account.dailyAmount() < 0
                || account.confidence() == LivingCostEstimator.Confidence.INSUFFICIENT
                || account.dataOrigin() == LivingCostEstimator.DataOrigin.INSUFFICIENT) {
            throw new IllegalStateException("생활비 예측값이 계약을 어겼다: accountId=" + account.accountId());
        }
        return new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("accountId", account.accountId())
                .addValue("predictedOn", predictedOn)
                .addValue("horizonDays", horizonDays)
                .addValue("method", account.method())
                .addValue("projectedAmount", account.projectedAmount())
                .addValue("dailyAmount", account.dailyAmount())
                .addValue("confidence", account.confidence().name())
                .addValue("dataOrigin", account.dataOrigin().name())
                .addValue("createdAt", createdAt);
    }
}
