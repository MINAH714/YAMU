package com.yamu.be.auth.dto;

import com.yamu.be.auth.domain.SignupMode;
import com.yamu.be.auth.domain.SignupStep;

/**
 * @param signupToken 가입 단계 API에 {@code Authorization: Bearer {signupToken}}으로 보낸다. 30분 유효
 * @param nextStep    앱이 띄울 다음 화면. 가입 중이던 사용자면 멈춘 단계
 */
public record PhoneVerificationConfirmResponse(String signupToken, long expiresIn, SignupMode mode, SignupStep nextStep) {
}
