package com.yamu.be.auth.dto;

import com.yamu.be.auth.domain.AuthMethod;
import com.yamu.be.auth.domain.StepUpPurpose;
import java.time.LocalDateTime;

/**
 * @param stepUpToken 대상 API의 X-Step-Up-Token 헤더에 넣는다. 5분 안에 한 번만 쓸 수 있다
 * @param auditLogId  이번 인증 기록 번호. 계좌 연결은 이 번호를 연결 기록에 남긴다
 */
public record StepUpVerifyResponse(String stepUpToken, StepUpPurpose purpose, AuthMethod method,
        LocalDateTime expiresAt, Long auditLogId) {
}
