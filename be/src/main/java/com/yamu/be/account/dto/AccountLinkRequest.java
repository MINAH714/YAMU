package com.yamu.be.account.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * @param authCode    1원 입금자명에 붙은 숫자 4자리. 형식이 틀리면 400으로 끝나 3번의 시도 횟수를 깎지 않는다
 * @param accountName 계좌 별칭(선택)
 */
public record AccountLinkRequest(
        @NotBlank(message = "계좌 확인 결과(verificationToken)가 필요합니다.")
        String verificationToken,
        @NotBlank(message = "인증코드가 필요합니다.")
        @Pattern(regexp = "\\d{4}", message = "인증코드는 숫자 4자리입니다.")
        String authCode,
        @Size(max = 20, message = "계좌 별칭은 20자까지입니다.")
        String accountName) {
}
