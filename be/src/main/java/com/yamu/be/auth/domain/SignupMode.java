package com.yamu.be.auth.domain;

/** 휴대폰 본인인증을 마친 사람이 무엇을 하러 왔는지. 이름·생년월일·번호로 시드 사용자를 찾아 정한다. */
public enum SignupMode {
    /** 가입 중(PENDING)인 시드 사용자 */
    SIGNUP,
    /** 이미 가입(ACTIVE)한 사용자의 폰 변경·PIN 분실. 연결된 계좌로 1원 인증 → PIN 재설정 → 기기 등록 */
    DEVICE_RECOVERY
}
