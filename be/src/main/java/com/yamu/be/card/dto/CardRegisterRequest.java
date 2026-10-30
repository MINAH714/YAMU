package com.yamu.be.card.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 카드 등록 요청. 출금 계좌(신용카드 결제계좌·체크카드 연결 계좌)와 결제일은 카드사(시드)가 정한 값을 쓰므로 받지 않는다.
 * 예전 앱이 계좌·결제일 필드를 보내도 쓰지 않는다(모르는 필드는 무시).
 */
public record CardRegisterRequest(
        @NotBlank(message = "카드 확인 토큰이 필요합니다.")
        String verificationToken,
        @Size(max = 20, message = "카드 별칭은 20자까지입니다.")
        String nickname) {

    /** 별칭은 앞뒤 공백을 먼저 빼고 길이를 본다. 비면 별칭 없음(null). */
    public CardRegisterRequest {
        nickname = nickname == null || nickname.isBlank() ? null : nickname.strip();
    }
}
