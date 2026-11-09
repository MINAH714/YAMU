package com.yamu.be.auth.domain;

/** step_up_audit_log.purpose. 로그인(LOGIN)과 명세 5장 본인 인증 용도. */
public enum StepUpPurpose {
    LOGIN,
    APP_UNLOCK,
    BARCODE,
    CARD_REGISTER,
    CARD_DELETE,
    ACCOUNT_LINK,
    SECURITY_SETTINGS,
    WITHDRAW_MEMBER,
    DATA_EXPORT
}
