package com.yamu.be.payment.result;

import com.yamu.be.global.common.ApiResponse;
import com.yamu.be.payment.pos.PosTerminalResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 모의 POS가 부르는 결제 내역(S15P31E103-423). 인증은 {@code X-POS-Key}다. */
@Tag(name = "모의 POS")
@RestController
@RequestMapping("/api/pos/payment-requests")
class PosPaymentHistoryController {

    private final PosPaymentHistoryService service;

    PosPaymentHistoryController(PosPaymentHistoryService service) {
        this.service = service;
    }

    @Operation(summary = "POS 결제 내역",
            description = "posCode의 POS가 만든 최근 7일 승인·취소 결제를 결제 번호 내림차순으로 최대 50건 돌려준다. 취소할 결제를 고를 때 쓴다. "
                    + "거절·만료·진행 중 결제는 없다. cancelable은 APPROVED일 때 true다. 설정에 없는 posCode는 404 MERCHANT_NOT_FOUND.")
    @GetMapping
    ApiResponse<PosPaymentHistory> list(
            @RequestHeader(name = PosTerminalResolver.HEADER, required = false) String posKey,
            @RequestParam String posCode) {
        return ApiResponse.ok(service.list(posKey, posCode));
    }
}
