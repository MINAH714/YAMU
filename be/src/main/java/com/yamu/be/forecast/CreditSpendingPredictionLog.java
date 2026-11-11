package com.yamu.be.forecast;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 잔고예보가 낸 신용카드 마감 전 사용 추정을 {@code credit_spending_prediction}에 남긴다(S15P31E103-405). 실사용 편향을 보는 재료다.
 * 규칙은 체크 생활비 이력({@link LivingCostPredictionLog}, -309)과 같다: 카드·한국 날짜·R마다 그날 첫 값 한 행, 이미 있는 행은 먼저 읽어
 * 건너뛰고 없는 카드만 넣는다. 동시 요청도 고유 키로 한 행이다. 가상 구매는 남기지 않는다(부르는 쪽이 잔고예보뿐이다).
 *
 * <p>저장이 실패해도 예보는 그대로 준다. DB 오류만 경고 로그(사용자·카드 번호와 예외 클래스 이름, 금액 없음)로 남기고, 예측값이 계약을
 * 어기면(음수 등) 숨기지 않고 던진다.
 */
@Slf4j
@Component
class CreditSpendingPredictionLog {
    private static final String INSERT = """
            INSERT INTO credit_spending_prediction (user_id, user_card_id, predicted_on, horizon_days, method,
                model_version, projected_amount, daily_amount, confidence, data_origin, created_at)
            VALUES (:userId, :userCardId, :predictedOn, :horizonDays, :method,
                :modelVersion, :projectedAmount, :dailyAmount, :confidence, :dataOrigin, :createdAt)
            ON DUPLICATE KEY UPDATE credit_spending_prediction_id = credit_spending_prediction_id
            """;
    private static final String SELECT_SAVED = """
            SELECT CONCAT(user_card_id, ':', horizon_days) FROM credit_spending_prediction
            WHERE user_card_id IN (:userCardIds) AND predicted_on = :predictedOn
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    CreditSpendingPredictionLog(NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * @param userId      카드 보유 사용자(부르는 쪽이 이미 소유·출금 계좌를 확인했다)
     * @param predictedOn 예측한 한국 날짜(예보 시작일). 예측 기간은 다음 날부터 카드마다 R일이다
     * @param predictions 카드별 예측({@link CreditSpendingEstimator.Result#predictions()})
     */
    void record(long userId, LocalDate predictedOn, Collection<CreditSpendingEstimator.Prediction> predictions) {
        LocalDateTime createdAt = LocalDateTime.now(clock.withZone(ZoneOffset.UTC));
        // 이미 저장한 카드여도 계약 검사는 매번 한다.
        List<MapSqlParameterSource> rows = predictions.stream()
                .map(prediction -> row(userId, predictedOn, prediction, createdAt))
                .toList();
        if (rows.isEmpty()) {
            return;
        }
        List<Long> userCardIds = rows.stream().map(row -> (Long) row.getValue("userCardId")).distinct().toList();
        try {
            // 같은 날 다시 조회하면 대개 모두 저장돼 있다. 읽기 한 번으로 끝내고 쓰기·잠금과 자동 증가 번호 소모를 피한다(-309와 같은 이유).
            Set<String> saved = Set.copyOf(jdbc.queryForList(SELECT_SAVED, new MapSqlParameterSource()
                    .addValue("userCardIds", userCardIds)
                    .addValue("predictedOn", predictedOn), String.class));
            MapSqlParameterSource[] missing = rows.stream()
                    .filter(row -> !saved.contains(row.getValue("userCardId") + ":" + row.getValue("horizonDays")))
                    .toArray(MapSqlParameterSource[]::new);
            if (missing.length > 0) {
                jdbc.batchUpdate(INSERT, missing);
            }
        } catch (DataAccessException e) {
            log.warn("신용 사용 추정 이력을 저장하지 못했다. 예보 응답은 그대로 준다. userId={}, userCardIds={}, cause={}",
                    userId, userCardIds, e.getClass().getSimpleName());
        }
    }

    private static MapSqlParameterSource row(long userId, LocalDate predictedOn,
            CreditSpendingEstimator.Prediction prediction, LocalDateTime createdAt) {
        LivingCostProjector.AccountProjection projection = prediction.projection();
        // 추정한 카드는 금액·하루 값·방식·신뢰도·출처가 모두 있고, AI면 모델 버전이 있고 TSB면 없다(LivingCostProjector 계약).
        // 어기면 조용히 넘기지 않는다.
        boolean ai = LivingCostProjector.METHOD_AI.equals(projection.method());
        if (prediction.remainingDays() < 1 || projection.projectedAmount() == null || projection.dailyAmount() == null
                || projection.method() == null || projection.projectedAmount() < 0 || projection.dailyAmount() < 0
                || projection.confidence() == LivingCostEstimator.Confidence.INSUFFICIENT
                || projection.dataOrigin() == LivingCostEstimator.DataOrigin.INSUFFICIENT
                || ai == (prediction.modelVersion() == null)) {
            throw new IllegalStateException("신용 사용 추정값이 계약을 어겼다: userCardId=" + prediction.userCardId());
        }
        return new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("userCardId", prediction.userCardId())
                .addValue("predictedOn", predictedOn)
                .addValue("horizonDays", prediction.remainingDays())
                .addValue("method", projection.method())
                .addValue("modelVersion", prediction.modelVersion())
                .addValue("projectedAmount", projection.projectedAmount())
                .addValue("dailyAmount", projection.dailyAmount())
                .addValue("confidence", projection.confidence().name())
                .addValue("dataOrigin", projection.dataOrigin().name())
                .addValue("createdAt", createdAt);
    }
}
