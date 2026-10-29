package com.yamu.be.account.dto;

import java.time.LocalDateTime;

/**
 * @param authExpiresAt       요청 시각 + 5분(실측 기준 안내). 최종 만료는 SSAFY가 판단한다
 * @param depositorNamePrefix 통장 입금자명 앞글자. 뒤에 붙은 숫자가 인증코드다
 */
public record OneWonAuthResponse(String verificationToken, LocalDateTime authRequestedAt, LocalDateTime authExpiresAt,
        String depositorNamePrefix, int codeLength, String hint) {
}
