package com.yamu.be.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.yamu.be.forecast.ForecastSettingsService;
import com.yamu.be.global.common.ApiResponse.FieldErrorResponse;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 알림 설정 조회·수정(Task 9.3.1). 명세: 노션 API 명세서 V2 「알림 설정 조회」「알림 설정 수정」, 모양은 앱 {@code MyModels.kt}.
 *
 * <p>앱 모양과 저장 묶음의 대응:
 * <ul>
 *   <li>{@code performance.enabled}·{@code performance.time} → performanceEnabled·performanceTime</li>
 *   <li>{@code balance.enabled}·{@code balance.time} → balanceRiskEnabled·balanceTime</li>
 *   <li>{@code payment.result} → transactionEnabled(거래 알림 전체)</li>
 *   <li>{@code balance.alertThreshold} → 잔고예보 설정의 안전 잔액을 읽기만 한다. 바꾸는 곳은 잔고예보 설정 API다</li>
 * </ul>
 * 알림을 꺼도 이미 만든 알림은 지우지 않는다.
 */
@Service
public class NotificationSettingsService {

    private static final String BOOLEAN = "true 또는 false여야 합니다.";
    private static final String TIME = "HH:mm 형식(00:00~23:59)이어야 합니다.";
    private static final String UNKNOWN = "바꿀 수 없는 설정입니다.";

    private final NotificationSettingsStore store;
    private final ForecastSettingsService forecastSettings;

    NotificationSettingsService(NotificationSettingsStore store, ForecastSettingsService forecastSettings) {
        this.store = store;
        this.forecastSettings = forecastSettings;
    }

    /** 내 알림 설정. 저장한 적이 없으면 모두 켜짐·08:30이다. */
    @Transactional(readOnly = true)
    public NotificationSettingsResponse get(long userId) {
        return response(userId, store.read(userId));
    }

    /**
     * 보낸 항목만 바꾸고 바뀐 설정 전체를 돌려준다.
     *
     * @param body PATCH 본문 그대로. 보내지 않은 항목과 null을 구분하려고 JSON 트리로 받는다
     * @throws BusinessException 형식 오류는 400 INVALID_REQUEST(fieldErrors, 틀린 항목을 한 번에), 사용자가 없으면 404
     */
    @Transactional
    public NotificationSettingsResponse update(long userId, JsonNode body) {
        Map<NotificationSettingsStore.Key, Object> changes = parse(body);
        store.apply(userId, changes);
        return response(userId, store.read(userId));
    }

    private NotificationSettingsResponse response(long userId, NotificationSettings settings) {
        return NotificationSettingsResponse.of(settings, forecastSettings.alertThreshold(userId));
    }

    /** 형식을 모두 검사해 틀린 항목을 한 번에 알린다. DB를 건드리기 전에 부른다. */
    static Map<NotificationSettingsStore.Key, Object> parse(JsonNode body) {
        if (body == null || !body.isObject() || body.isEmpty()) {
            throw invalid(List.of(new FieldErrorResponse("body", "바꿀 설정을 하나 이상 보내 주세요.")));
        }
        Map<NotificationSettingsStore.Key, Object> changes = new EnumMap<>(NotificationSettingsStore.Key.class);
        List<FieldErrorResponse> errors = new ArrayList<>();
        for (Map.Entry<String, JsonNode> field : body.properties()) {
            String group = field.getKey();
            JsonNode value = field.getValue();
            switch (group) {
                case "performance" -> group(group, value, errors, (name, leaf) -> switch (name) {
                    case "enabled" -> bool(group, name, leaf, NotificationSettingsStore.Key.PERFORMANCE_ENABLED,
                            changes, errors);
                    case "time" -> time(group, name, leaf, NotificationSettingsStore.Key.PERFORMANCE_TIME, changes,
                            errors);
                    default -> false;
                });
                case "balance" -> group(group, value, errors, (name, leaf) -> switch (name) {
                    case "enabled" -> bool(group, name, leaf, NotificationSettingsStore.Key.BALANCE_RISK_ENABLED,
                            changes, errors);
                    case "time" -> time(group, name, leaf, NotificationSettingsStore.Key.BALANCE_TIME, changes, errors);
                    case "alertThreshold" -> {
                        errors.add(new FieldErrorResponse("balance.alertThreshold", "안전 잔액은 잔고예보 설정에서 바꿔 주세요."));
                        yield true;
                    }
                    default -> false;
                });
                case "payment" -> group(group, value, errors, (name, leaf) -> name.equals("result")
                        && bool(group, name, leaf, NotificationSettingsStore.Key.TRANSACTION_ENABLED, changes, errors));
                case "securityEnabled", "security" -> errors.add(new FieldErrorResponse(group, "보안 알림은 끌 수 없습니다."));
                default -> errors.add(new FieldErrorResponse(group, UNKNOWN));
            }
        }
        if (!errors.isEmpty()) {
            throw invalid(errors);
        }
        if (changes.isEmpty()) {
            throw invalid(List.of(new FieldErrorResponse("body", "바꿀 설정을 하나 이상 보내 주세요.")));
        }
        return changes;
    }

    /** 묶음 안의 항목 하나를 처리한다. 아는 항목이면 true(오류여도), 모르는 항목이면 false. */
    @FunctionalInterface
    private interface Leaf {
        boolean handle(String name, JsonNode value);
    }

    private static void group(String group, JsonNode value, List<FieldErrorResponse> errors, Leaf leaf) {
        if (value == null || !value.isObject()) {
            errors.add(new FieldErrorResponse(group, "{ \"enabled\": true }처럼 객체로 보내 주세요."));
            return;
        }
        for (Map.Entry<String, JsonNode> field : value.properties()) {
            if (!leaf.handle(field.getKey(), field.getValue())) {
                errors.add(new FieldErrorResponse(group + "." + field.getKey(), UNKNOWN));
            }
        }
    }

    private static boolean bool(String group, String name, JsonNode value, NotificationSettingsStore.Key key,
            Map<NotificationSettingsStore.Key, Object> changes, List<FieldErrorResponse> errors) {
        if (value.isBoolean()) {
            changes.put(key, value.booleanValue());
        } else {
            errors.add(new FieldErrorResponse(group + "." + name, BOOLEAN));
        }
        return true;
    }

    private static boolean time(String group, String name, JsonNode value, NotificationSettingsStore.Key key,
            Map<NotificationSettingsStore.Key, Object> changes, List<FieldErrorResponse> errors) {
        LocalTime time = value.isTextual() ? NotificationSettings.parseTime(value.asText()) : null;
        if (time != null) {
            changes.put(key, time);
        } else {
            errors.add(new FieldErrorResponse(group + "." + name, TIME));
        }
        return true;
    }

    private static BusinessException invalid(List<FieldErrorResponse> errors) {
        return new BusinessException(ErrorCode.INVALID_REQUEST, Map.of("fieldErrors", List.copyOf(errors)));
    }
}
