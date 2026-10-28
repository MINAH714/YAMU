package com.yamu.be.auth.domain;

/** 본인 확인 수단. step_up_audit_log.auth_method, 로그인 요청의 method. */
public enum AuthMethod {
    /** 기기 키 서명 + 간편 비밀번호 */
    PIN,
    /** 생체 키 서명(지문을 찍어야 서명됨) */
    BIOMETRIC
}
