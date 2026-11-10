package com.yamu.be.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * {@code app_user.notification_settings} JSON 칸만 SQL로 읽고 쓴다. {@code AppUser} 엔티티(BE1)에 매핑하지 않아,
 * 다른 기능이 사용자 행을 저장해도 이 칸을 덮지 않는다. 잔고예보의 {@code ForecastSettingsStore}와 같은 방식이다.
 *
 * <p>쓰기는 바꿀 키만 {@code JSON_SET} 한 번으로 고친다. 같은 사용자가 다른 항목을 동시에 바꿔도 행 잠금 안에서 키별로 쓰므로
 * 한쪽 변경이 사라지지 않는다(읽고-고치고-쓰기를 하지 않는다).
 *
 * <p>DB 값은 믿지 않는다. 키가 없으면 기본값이지만, 형식이 틀린 값은 기본값으로 덮지 않고 실패한다.
 * 오류 메시지·로그에는 사용자 번호만 넣는다.
 *
 * <p>{@code @Repository}가 아니라 {@code @Component}다. 예외 변환이 저장값 오류(IllegalStateException)를 DB 사용 오류로 바꾸지 않게.
 */
@Component
class NotificationSettingsStore {

    /** 저장하는 JSON 키. 이름은 SQL 경로에 그대로 들어가므로 이 enum의 고정 값만 쓴다. */
    enum Key {
        PERFORMANCE_ENABLED("performanceEnabled"),
        BALANCE_RISK_ENABLED("balanceRiskEnabled"),
        TRANSACTION_ENABLED("transactionEnabled"),
        PERFORMANCE_TIME("performanceTime"),
        BALANCE_TIME("balanceTime");

        final String json;

        Key(String json) {
            this.json = json;
        }
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    NotificationSettingsStore(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** 사용자의 설정. 저장한 적이 없으면 기본값이다. 사용자가 없으면 404 RESOURCE_NOT_FOUND. */
    NotificationSettings read(long userId) {
        List<String> rows = jdbc.queryForList("SELECT notification_settings FROM app_user WHERE user_id = :userId",
                new MapSqlParameterSource("userId", userId), String.class);
        if (rows.isEmpty()) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return parse(userId, rows.get(0));
    }

    /**
     * 보낸 키만 바꾼다. 값은 boolean(…_ENABLED) 또는 {@link LocalTime}(…_TIME)이다. 사용자 행이 없으면 아무것도 바꾸지 않는다.
     *
     * @param changes 비어 있으면 안 된다
     */
    void apply(long userId, Map<Key, Object> changes) {
        if (changes.isEmpty()) {
            throw new IllegalArgumentException("바꿀 설정이 없다");
        }
        StringBuilder sets = new StringBuilder();
        MapSqlParameterSource params = new MapSqlParameterSource("userId", userId);
        int i = 0;
        for (Map.Entry<Key, Object> change : changes.entrySet()) {
            String param = "v" + i++;
            sets.append(", '$.").append(change.getKey().json).append("', ");
            if (change.getValue() instanceof Boolean value) {
                // 그냥 TRUE를 넣으면 JSON 숫자 1로 저장된다. JSON 리터럴로 바꿔 true/false로 저장한다.
                sets.append("CAST(:").append(param).append(" AS JSON)");
                params.addValue(param, value.toString());
            } else if (change.getValue() instanceof LocalTime value) {
                sets.append(':').append(param);
                params.addValue(param, NotificationSettings.format(value));
            } else {
                throw new IllegalArgumentException("알 수 없는 설정 값: " + change.getKey());
            }
        }
        jdbc.update("""
                UPDATE app_user SET notification_settings = JSON_SET(
                       CASE WHEN notification_settings IS NULL OR JSON_TYPE(notification_settings) = 'NULL'
                            THEN JSON_OBJECT() ELSE notification_settings END%s)
                 WHERE user_id = :userId
                """.formatted(sets), params);
    }

    private NotificationSettings parse(long userId, String json) {
        if (json == null) {
            return NotificationSettings.DEFAULTS;
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw broken(userId);
        }
        if (node == null || node.isNull()) {
            return NotificationSettings.DEFAULTS;
        }
        if (!node.isObject()) {
            throw broken(userId);
        }
        NotificationSettings defaults = NotificationSettings.DEFAULTS;
        return new NotificationSettings(
                bool(userId, node.get(Key.PERFORMANCE_ENABLED.json), defaults.performanceEnabled()),
                bool(userId, node.get(Key.BALANCE_RISK_ENABLED.json), defaults.balanceRiskEnabled()),
                bool(userId, node.get(Key.TRANSACTION_ENABLED.json), defaults.transactionEnabled()),
                time(userId, node.get(Key.PERFORMANCE_TIME.json), defaults.performanceTime()),
                time(userId, node.get(Key.BALANCE_TIME.json), defaults.balanceTime()));
    }

    private static boolean bool(long userId, JsonNode value, boolean defaultValue) {
        if (value == null || value.isNull()) {
            return defaultValue;
        }
        if (!value.isBoolean()) {
            throw broken(userId);
        }
        return value.booleanValue();
    }

    private static LocalTime time(long userId, JsonNode value, LocalTime defaultValue) {
        if (value == null || value.isNull()) {
            return defaultValue;
        }
        LocalTime time = value.isTextual() ? NotificationSettings.parseTime(value.asText()) : null;
        if (time == null) {
            throw broken(userId);
        }
        return time;
    }

    private static IllegalStateException broken(long userId) {
        return new IllegalStateException("알림 설정 저장값이 올바르지 않다 (userId=" + userId + ")");
    }
}
