package com.yamu.be.forecast;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * {@code app_user.forecast_settings} JSON 칸만 SQL로 읽고 쓴다. 이 칸은 잔고예보만 쓰므로 {@code AppUser} 엔티티에 매핑하지 않았다.
 * JPA는 매핑된 칸만 UPDATE하므로 다른 기능이 사용자 행을 저장해도 이 칸을 덮지 않는다.
 *
 * <p>DB 값은 믿지 않는다. 키가 없으면 기본값이지만, 읽을 수 없거나 범위 밖인 값은 기본값으로 덮지 않고 실패한다.
 * 오류 메시지·로그에는 사용자 번호만 넣고 저장값은 넣지 않는다.
 *
 * <p>{@code @Repository}가 아니라 {@code @Component}다. {@code @Repository}의 예외 변환이 저장값 오류(IllegalStateException)를
 * DB 사용 오류로 바꾸기 때문이다. SQL 예외는 {@link NamedParameterJdbcTemplate}이 이미 바꿔 준다.
 */
@Component
class ForecastSettingsStore {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    ForecastSettingsStore(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** 사용자의 설정. 저장한 적이 없으면 기본값이다. 사용자가 없으면 404 RESOURCE_NOT_FOUND. */
    ForecastSettings read(long userId) {
        return parse(userId, select(userId, ""));
    }

    /** 사용자 행을 잠그고({@code FOR UPDATE}) 설정을 읽는다. 트랜잭션 안에서만 부른다. 같은 사용자의 수정이 차례로 처리된다. */
    ForecastSettings readForUpdate(long userId) {
        return parse(userId, select(userId, " FOR UPDATE"));
    }

    /** 설정 전체를 저장한다. 키는 항상 모두 쓴다. */
    void write(long userId, ForecastSettings settings) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("horizon", settings.horizon().name());
        node.put("defaultDays", settings.defaultDays());
        node.put("alertThreshold", settings.alertThreshold());
        node.put("incomeCashflowItemId", settings.incomeCashflowItemId());
        node.put("dailyLivingCostTarget", settings.dailyLivingCostTarget());
        node.put("includeCandidates", settings.includeCandidates());
        node.put("conservativeLine", settings.conservativeLine());
        node.put("updatedAt", settings.updatedAt() == null ? null
                : settings.updatedAt().toInstant(ZoneOffset.UTC).toString());
        jdbc.update("UPDATE app_user SET forecast_settings = :settings WHERE user_id = :userId",
                new MapSqlParameterSource("settings", node.toString()).addValue("userId", userId));
    }

    private String select(long userId, String lock) {
        List<String> rows = jdbc.queryForList("SELECT forecast_settings FROM app_user WHERE user_id = :userId" + lock,
                new MapSqlParameterSource("userId", userId), String.class);
        if (rows.isEmpty()) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return rows.get(0);
    }

    private ForecastSettings parse(long userId, String json) {
        if (json == null) {
            return ForecastSettings.DEFAULTS;
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw broken(userId);
        }
        if (!node.isObject()) {
            throw broken(userId);
        }
        ForecastSettings defaults = ForecastSettings.DEFAULTS;
        BalanceForecast.Until horizon = defaults.horizon();
        JsonNode value = node.get("horizon");
        if (present(value)) {
            if (!value.isTextual() || !(value.asText().equals("NEXT_INCOME") || value.asText().equals("DAYS"))) {
                throw broken(userId);
            }
            horizon = BalanceForecast.Until.valueOf(value.asText());
        }
        Long days = number(userId, node.get("defaultDays"), 7, 30);
        if (days != null && !ForecastSettings.ALLOWED_DAYS.contains(days.intValue())) {
            throw broken(userId);
        }
        Long threshold = number(userId, node.get("alertThreshold"), 0, ForecastSettings.MAX_AMOUNT);
        return new ForecastSettings(horizon,
                days == null ? defaults.defaultDays() : days.intValue(),
                threshold == null ? defaults.alertThreshold() : threshold,
                number(userId, node.get("incomeCashflowItemId"), 1, Long.MAX_VALUE),
                number(userId, node.get("dailyLivingCostTarget"), 1, ForecastSettings.MAX_AMOUNT),
                bool(userId, node.get("includeCandidates"), defaults.includeCandidates()),
                bool(userId, node.get("conservativeLine"), defaults.conservativeLine()),
                time(userId, node.get("updatedAt")));
    }

    private static boolean present(JsonNode value) {
        return value != null && !value.isNull();
    }

    private static Long number(long userId, JsonNode value, long min, long max) {
        if (!present(value)) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw broken(userId);
        }
        long number = value.longValue();
        if (number < min || number > max) {
            throw broken(userId);
        }
        return number;
    }

    private static boolean bool(long userId, JsonNode value, boolean defaultValue) {
        if (!present(value)) {
            return defaultValue;
        }
        if (!value.isBoolean()) {
            throw broken(userId);
        }
        return value.booleanValue();
    }

    private static LocalDateTime time(long userId, JsonNode value) {
        if (!present(value)) {
            return null;
        }
        if (!value.isTextual()) {
            throw broken(userId);
        }
        try {
            return LocalDateTime.ofInstant(Instant.parse(value.asText()), ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            throw broken(userId);
        }
    }

    private static IllegalStateException broken(long userId) {
        return new IllegalStateException("잔고예보 설정 저장값이 올바르지 않다 (userId=" + userId + ")");
    }
}
