package com.yamu.be.payment.cancel;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * POS 결제 취소 요청 본문(S15P31E103-424). 고객이 앱에서 띄운 결제 바코드를 POS가 찍어 본인 확인으로 쓴다.
 *
 * @param posCode      결제를 만든 POS의 코드. 다르면 404
 * @param barcodeToken 고객 앱의 결제 바코드 값. 만료 전 ISSUED여야 하고, 취소가 끝나면 닫힌다
 */
public record PosPaymentCancelRequest(
        @Schema(description = "결제를 만든 POS 코드", example = "POS-STARBUCKS-GENERAL")
        @NotBlank(message = "POS 코드를 입력해 주세요.")
        String posCode,
        @Schema(description = "고객 앱의 결제 바코드 값", example = "1234567890123456")
        @NotBlank(message = "고객 바코드를 스캔해 주세요.")
        String barcodeToken) {}
