package com.yamu.be.auth.domain;

/** 기기 등록 요청의 biometricTypes. 저장 칸이 없어 검사만 한다. FACE는 시연 기기가 BIOMETRIC_STRONG일 때만(DEC-012). */
public enum BiometricType {
    FINGERPRINT,
    FACE
}
