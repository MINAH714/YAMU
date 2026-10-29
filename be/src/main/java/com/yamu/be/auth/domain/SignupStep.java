package com.yamu.be.auth.domain;

import java.util.Arrays;
import java.util.List;

/** 가입 단계. 이 순서대로 진행한다(DEC-001). 앱은 nextStep을 보고 그 화면부터 띄운다. */
public enum SignupStep {
    SIGNUP,
    ACCOUNT_LINK,
    PIN_SETUP,
    DEVICE_REGISTER;

    /** 이 단계부터 끝까지. 예: ACCOUNT_LINK → [ACCOUNT_LINK, PIN_SETUP, DEVICE_REGISTER] */
    public List<SignupStep> andAfter() {
        return Arrays.stream(values()).filter(s -> s.ordinal() >= ordinal()).toList();
    }
}
