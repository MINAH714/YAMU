package com.yamu.be.card.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * @param cardNumber  카드번호. 숫자 16자리인지는 Service가 확인한다(명세: 자릿수 오류는 INVALID_CARD_NUMBER)
 * @param expiryMonth 유효기간 월 MM
 * @param expiryYear  유효기간 연도 YY
 */
public record CardLookupRequest(
        @NotBlank(message = "카드번호가 필요합니다.")
        String cardNumber,
        @NotBlank(message = "유효기간 월이 필요합니다.")
        @Pattern(regexp = "0[1-9]|1[0-2]", message = "유효기간 월은 01~12로 입력해 주세요.")
        String expiryMonth,
        @NotBlank(message = "유효기간 연도가 필요합니다.")
        @Pattern(regexp = "[0-9]{2}", message = "유효기간 연도는 숫자 2자리(YY)로 입력해 주세요.")
        String expiryYear) {

    /** 카드번호 원문이 로그·예외 메시지에 찍히지 않게 끝 4자리만 남긴다. */
    @Override
    public String toString() {
        String last4 = cardNumber == null || cardNumber.length() < 4 ? "****" : cardNumber.substring(cardNumber.length() - 4);
        return "CardLookupRequest[cardNumber=************" + last4 + ", expiryMonth=" + expiryMonth
                + ", expiryYear=" + expiryYear + "]";
    }
}
