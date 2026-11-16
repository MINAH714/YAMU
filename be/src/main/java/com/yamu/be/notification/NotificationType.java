package com.yamu.be.notification;

/**
 * 앱 내 알림 유형({@code notification.notification_type}). 노션 API 명세서 V2 「알림 목록 조회」 표의 값이다.
 * 알림을 만드는 코드는 Task 9.3.2(S15P31E103-377)에서 붙는다. 여기서는 목록의 {@code type} 필터 값을 검사하는 데 쓴다.
 *
 * <p>알림 설정의 묶음: 실적({@link #PERFORMANCE_SHORTFALL}), 잔고 위험({@link #BALANCE_RISK}),
 * 거래(결제 결과·청구·출금·환급). 반복 지출 후보·혜택 변경·보안은 설정과 상관없이 만든다.
 *
 * <p>지금 만들지 않는 유형도 값은 남긴다. 목록의 {@code type} 필터 값이라 지우면 그 값을 보내는 앱이 400을 받는다.
 * 출금 예정({@link #BILL_DUE_SOON}), 미납({@link #BILL_UNPAID}, Task 9.3.4에서 뺌, 2026-11-13 결정).
 */
public enum NotificationType {
    PAYMENT_RESULT,
    PERFORMANCE_SHORTFALL,
    BALANCE_RISK,
    BILL_CONFIRMED,
    BILL_DUE_SOON,
    BILL_WITHDRAWN,
    BILL_UNPAID,
    REFUND_COMPLETED,
    CASHFLOW_CANDIDATE,
    BENEFIT_CHANGE,
    SECURITY
}
