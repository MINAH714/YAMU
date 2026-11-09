package com.yamu.be.user.domain;

/** 결제 코드를 열 때 인증 방식(명세 「보안 설정 수정」). */
public enum BarcodeAuthMethod {
    /** 지문 또는 PIN(기본) */
    BIOMETRIC_OR_PIN,
    /** PIN만 */
    PIN_ONLY
}
