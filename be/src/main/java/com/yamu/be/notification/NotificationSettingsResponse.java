package com.yamu.be.notification;

/**
 * 알림 설정(Task 9.3.1). 앱 {@code MyModels.kt}의 {@code NotificationSettingsDto}가 읽는 모양이다.
 * 저장은 DB 문서의 세 묶음({@link NotificationSettings})이고, 이 응답은 그것을 앱 모양으로 옮긴 것이다.
 *
 * <pre>
 * { "performance": { "enabled": true, "time": "08:30" },
 *   "balance": { "enabled": true, "alertThreshold": 100000, "time": "08:30" },
 *   "payment": { "result": true },
 *   "securityEnabled": true }
 * </pre>
 *
 * @param securityEnabled 보안 알림은 끌 수 없어 항상 true(읽기 전용)
 */
public record NotificationSettingsResponse(Performance performance, Balance balance, Payment payment,
        boolean securityEnabled) {

    /** @param time 한국 시각 "HH:mm" */
    public record Performance(boolean enabled, String time) {}

    /**
     * @param alertThreshold 안전 잔액(원). 잔고예보 설정({@code forecast_settings.alertThreshold})과 같은 값이며 여기서는 바꿀 수 없다
     * @param time           한국 시각 "HH:mm"
     */
    public record Balance(boolean enabled, long alertThreshold, String time) {}

    /** @param result 거래 알림(결제 결과·청구 확정·출금 예정·출금·미납·환급)을 받을지. DB의 transactionEnabled */
    public record Payment(boolean result) {}

    static NotificationSettingsResponse of(NotificationSettings settings, long alertThreshold) {
        return new NotificationSettingsResponse(
                new Performance(settings.performanceEnabled(), NotificationSettings.format(settings.performanceTime())),
                new Balance(settings.balanceRiskEnabled(), alertThreshold,
                        NotificationSettings.format(settings.balanceTime())),
                new Payment(settings.transactionEnabled()),
                true);
    }
}
