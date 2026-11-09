package com.yamu.be.auth.domain;

/** 가입 단계. 이 순서대로 진행한다(DEC-001). 앱은 nextStep을 보고 그 화면부터 띄운다. */
public enum SignupStep {
    ACCOUNT_LINK,
    PIN_SETUP,
    DEVICE_REGISTER
}
