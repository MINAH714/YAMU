package com.yamu.be.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;

/**
 * @param verificationId 확인 API에 쓰는 번호. 3분 동안 유효하다
 * @param demoCode       시연 서버에서만 내려주는 인증번호. 앱은 "인증번호가 발송됐어요" 화면에 띄운다. 끄면 필드가 빠진다
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PhoneVerificationResponse(String verificationId, LocalDateTime expiresAt, String demoCode) {
}
