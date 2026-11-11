package com.yamu.be.notification;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

/**
 * 알림 설정의 저장 형태({@code app_user.notification_settings} JSON). 설정 묶음은 DB 문서(implementation.md)의 세 가지다.
 *
 * <pre>
 * { "performanceEnabled": true, "balanceRiskEnabled": true, "transactionEnabled": true,
 *   "performanceTime": "08:30", "balanceTime": "08:30" }
 * </pre>
 *
 * <p>NULL이거나 키가 없으면 켜짐·08:30으로 본다. 저장된 false는 그대로 둔다. 시각은 한국 시각 기준 "매일 몇 시"라 UTC로 바꾸지 않는다
 * (2026-11-10 결정: 앱 알림 설정 화면이 시각을 보내므로 저장한다. 실제 발송에 반영하는 것은 Task 9.3.2).
 *
 * @param performanceEnabled 실적 부족 알림을 만들지
 * @param balanceRiskEnabled 잔고 위험 알림을 만들지
 * @param transactionEnabled 거래 알림(결제 결과·청구 확정·출금 예정·출금·미납·환급)을 만들지. 앱의 {@code payment.result}
 * @param performanceTime    실적 알림 받을 시각(한국 시각)
 * @param balanceTime        잔고 알림 받을 시각(한국 시각)
 */
record NotificationSettings(boolean performanceEnabled, boolean balanceRiskEnabled, boolean transactionEnabled,
        LocalTime performanceTime, LocalTime balanceTime) {

    static final LocalTime DEFAULT_TIME = LocalTime.of(8, 30);

    static final NotificationSettings DEFAULTS = new NotificationSettings(true, true, true, DEFAULT_TIME, DEFAULT_TIME);

    /** "HH:mm" 24시간, 00:00~23:59. "8:30"·"24:00"·"08:30:00"은 받지 않는다. */
    private static final Pattern TIME = Pattern.compile("([01][0-9]|2[0-3]):[0-5][0-9]");

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    /** 형식에 맞으면 시각, 아니면 null. */
    static LocalTime parseTime(String text) {
        return text != null && TIME.matcher(text).matches() ? LocalTime.parse(text, FORMAT) : null;
    }

    static String format(LocalTime time) {
        return time.format(FORMAT);
    }
}
