package com.yamu.be.account.dto;

import jakarta.validation.constraints.NotBlank;

/** @param accountNo SSAFY 계좌번호. 숫자 16자리인지는 Service가 확인한다(명세: 자릿수 오류는 INVALID_ACCOUNT_NUMBER) */
public record AccountVerifyRequest(
        @NotBlank(message = "계좌번호가 필요합니다.")
        String accountNo) {
}
