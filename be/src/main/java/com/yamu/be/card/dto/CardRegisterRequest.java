package com.yamu.be.card.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 카드 등록 요청. 신용카드는 결제일·결제계좌, 체크카드는 연결 계좌를 보낸다. 종류에 맞지 않는 값은 쓰지 않는다.
 *
 * @param paymentDay          신용카드 결제일 1~27. 범위·누락은 Service가 INVALID_PAYMENT_DAY로 거절한다
 * @param settlementAccountId 신용카드 청구액을 출금할 연결 계좌
 * @param linkedAccountId     체크카드 결제 즉시 출금할 연결 계좌
 */
public record CardRegisterRequest(
        @NotBlank(message = "카드 확인 토큰이 필요합니다.")
        String verificationToken,
        @Size(max = 20, message = "카드 별칭은 20자까지입니다.")
        String nickname,
        Integer paymentDay,
        Long settlementAccountId,
        Long linkedAccountId) {

    /** 별칭은 앞뒤 공백을 먼저 빼고 길이를 본다. 비면 별칭 없음(null). */
    public CardRegisterRequest {
        nickname = nickname == null || nickname.isBlank() ? null : nickname.strip();
    }
}
