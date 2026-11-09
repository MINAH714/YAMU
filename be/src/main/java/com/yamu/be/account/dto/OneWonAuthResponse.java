package com.yamu.be.account.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;

/**
 * @param bank                1원을 보낸 계좌의 은행
 * @param maskedAccountNo     1원을 보낸 계좌(끝 4자리)
 * @param authExpiresAt       요청 시각 + 5분(실측 기준 안내). 최종 만료는 SSAFY가 판단한다
 * @param depositorNamePrefix 통장 입금자명 앞글자. 뒤에 붙은 숫자가 인증코드다
 * @param deliveryUncertain   금융망 응답이 늦어 1원이 나갔는지 확실하지 않음. 보낸 것으로 보고 코드를 받을 수 있게 하되,
 *                            거래내역에 없으면 1분 뒤 다시 요청하라고 안내한다
 * @param demoCode            시연 서버에서만 내려주는 1원 인증 숫자(휴대폰 인증번호의 demoCode와 같은 방식). 시연 계좌는 SSAFY
 *                            금융망에만 있어 사용자가 거래내역을 볼 수 없어서다. 끄거나 찾지 못하면 필드가 빠진다(임시)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OneWonAuthResponse(String verificationToken, BankResponse bank, String maskedAccountNo,
        LocalDateTime authRequestedAt, LocalDateTime authExpiresAt, String depositorNamePrefix, int codeLength,
        String hint, boolean deliveryUncertain, String demoCode) {
}
