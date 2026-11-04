package com.yamu.be.payment.result;

import com.yamu.be.global.common.ApiResponse;
import com.yamu.be.payment.pos.PosTerminalResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 모의 POS가 부르는 결제 결과 조회. 인증은 {@code X-POS-Key}다(API 명세 「POS 결제 결과 조회」). */
@Tag(name = "모의 POS")
@RestController
@RequestMapping("/api/pos/payment-requests")
class PosPaymentResultController {

    private final PosPaymentResultService service;

    PosPaymentResultController(PosPaymentResultService service) {
        this.service = service;
    }

    @Operation(summary = "POS 결제 결과 조회",
            description = "POS가 2초마다 불러 APPROVED·EXPIRED, 또는 retryable이 false인 DECLINED가 되면 영수증을 띄운다. "
                    + "posCode는 결제 요청을 만든 POS의 코드이고 다르면 404다. 마감(60초)이 지난 REQUESTED·DECLINED는 조회 때 EXPIRED가 된다.")
    @GetMapping("/{paymentId}")
    ApiResponse<PosPaymentResult> get(
            @RequestHeader(name = PosTerminalResolver.HEADER, required = false) String posKey,
            @PathVariable Long paymentId,
            @RequestParam String posCode) {
        return ApiResponse.ok(service.get(posKey, posCode, paymentId));
    }
}
