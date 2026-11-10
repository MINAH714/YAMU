package com.yamu.be.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yamu.be.global.common.ApiResponse.FieldErrorResponse;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.notification.NotificationSettingsStore.Key;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 알림 설정 PATCH 본문 검사. DB 없이 돈다. */
class NotificationSettingsParseTest {

    final ObjectMapper mapper = new ObjectMapper();

    @Test
    void 앱_모양을_저장_키로_옮긴다() throws Exception {
        Map<Key, Object> changes = parse("""
                { "performance": { "enabled": false, "time": "21:05" },
                  "balance": { "enabled": true, "time": "00:00" },
                  "payment": { "result": false } }
                """);

        assertThat(changes).containsExactly(
                Map.entry(Key.PERFORMANCE_ENABLED, false),
                Map.entry(Key.BALANCE_RISK_ENABLED, true),
                Map.entry(Key.TRANSACTION_ENABLED, false),
                Map.entry(Key.PERFORMANCE_TIME, LocalTime.of(21, 5)),
                Map.entry(Key.BALANCE_TIME, LocalTime.MIDNIGHT));
    }

    @Test
    void 한_항목만_보내면_그것만_바꾼다() throws Exception {
        assertThat(parse("{ \"payment\": { \"result\": true } }"))
                .containsExactly(Map.entry(Key.TRANSACTION_ENABLED, true));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{}", "[]", "\"x\"", "{ \"performance\": {} }"})
    void 바꿀_항목이_없으면_400이다(String body) {
        assertThat(errors(body)).containsExactly(
                new FieldErrorResponse("body", "바꿀 설정을 하나 이상 보내 주세요."));
    }

    @Test
    void 본문이_없어도_400이다() {
        assertThatThrownBy(() -> NotificationSettingsService.parse(null))
                .isInstanceOf(BusinessException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"24:00", "8:30", "08:30:00", "08:60", " 08:30", "", "0830"})
    void 시각은_HH_mm만_받는다(String time) {
        assertThat(errors("{ \"performance\": { \"time\": \"" + time + "\" } }")).containsExactly(
                new FieldErrorResponse("performance.time", "HH:mm 형식(00:00~23:59)이어야 합니다."));
    }

    @Test
    void 틀린_항목을_한_번에_모두_알린다() {
        assertThat(errors("""
                { "performance": { "enabled": "true", "time": null, "dDays": [7] },
                  "balance": { "enabled": null, "alertThreshold": 50000 },
                  "payment": { "result": 1, "refund": true },
                  "securityEnabled": false,
                  "report": { "monthly": true },
                  "billing": true }
                """)).containsExactlyInAnyOrder(
                new FieldErrorResponse("performance.enabled", "true 또는 false여야 합니다."),
                new FieldErrorResponse("performance.time", "HH:mm 형식(00:00~23:59)이어야 합니다."),
                new FieldErrorResponse("performance.dDays", "바꿀 수 없는 설정입니다."),
                new FieldErrorResponse("balance.enabled", "true 또는 false여야 합니다."),
                new FieldErrorResponse("balance.alertThreshold", "안전 잔액은 잔고예보 설정에서 바꿔 주세요."),
                new FieldErrorResponse("payment.result", "true 또는 false여야 합니다."),
                new FieldErrorResponse("payment.refund", "바꿀 수 없는 설정입니다."),
                new FieldErrorResponse("securityEnabled", "보안 알림은 끌 수 없습니다."),
                new FieldErrorResponse("report", "바꿀 수 없는 설정입니다."),
                new FieldErrorResponse("billing", "바꿀 수 없는 설정입니다."));
    }

    @Test
    void 묶음이_객체가_아니면_400이다() {
        assertThat(errors("{ \"performance\": false }")).containsExactly(
                new FieldErrorResponse("performance", "{ \"enabled\": true }처럼 객체로 보내 주세요."));
    }

    private Map<Key, Object> parse(String json) throws Exception {
        return NotificationSettingsService.parse(mapper.readTree(json));
    }

    @SuppressWarnings("unchecked")
    private List<FieldErrorResponse> errors(String json) {
        JsonNode body;
        try {
            body = json.isEmpty() ? mapper.missingNode() : mapper.readTree(json);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        BusinessException e = (BusinessException) org.assertj.core.api.Assertions.catchThrowable(
                () -> NotificationSettingsService.parse(body));
        assertThat(e).isNotNull();
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
        return (List<FieldErrorResponse>) ((Map<String, Object>) e.getDetails()).get("fieldErrors");
    }
}
