package com.yamu.be.payment.domain;

/**
 * 입점 시설 코드(DB {@code payment_transaction.facility_type}). 2026-10-27 확정 10종(스키마 사전 payment_transaction 절).
 * 미확인은 NULL로 두고 GENERAL로 대체하지 않는다. GENERAL은 아래 시설에 입점하지 않은 것으로 확인한 경우에만 쓴다.
 */
public enum FacilityType {
    GENERAL,
    DEPARTMENT_STORE,
    DISCOUNT_STORE,
    DUTY_FREE,
    OUTLET,
    SHOPPING_MALL,
    AIRPORT,
    HOTEL,
    RESORT,
    TRAIN_STATION
}
