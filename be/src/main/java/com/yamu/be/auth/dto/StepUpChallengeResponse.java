package com.yamu.be.auth.dto;

import com.yamu.be.auth.domain.AuthMethod;
import com.yamu.be.auth.domain.StepUpPurpose;
import java.time.LocalDateTime;
import java.util.List;

/**
 * @param allowedMethods 이 기기에서 쓸 수 있는 방식. 생체를 켠 기기면 BIOMETRIC·PIN, 아니면 PIN
 * @param expiresAt      challenge 만료 시각(UTC, 60초 뒤)
 */
public record StepUpChallengeResponse(String challengeId, String challenge, StepUpPurpose purpose,
        List<AuthMethod> allowedMethods, LocalDateTime expiresAt) {
}
