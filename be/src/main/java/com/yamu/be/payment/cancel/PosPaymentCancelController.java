package com.yamu.be.payment.cancel;

import com.yamu.be.global.common.ApiResponse;
import com.yamu.be.payment.pos.PosTerminalResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 모의 POS가 부르는 결제 취소(S15P31E103-424). 인증은 {@code X-POS-Key}이고, 고객 결제 바코드로 본인을 확인한다. */
@Tag(name = "모의 POS")
@RestController
@RequestMapping("/api/pos/payment-requests")
class PosPaymentCancelController {

    static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    private final PosPaymentCancelService service;

    PosPaymentCancelController(PosPaymentCancelService service) {
        this.service = service;
    }

    @Operation(summary = "POS 결제 취소 (고객 바코드 스캔)",
            description = "POS 결제 내역에서 고른 결제를 고객 앱의 결제 바코드로 본인 확인한 뒤 전체 취소한다. 취소 처리는 앱 결제 취소와 같다"
                    + "(체크카드 환불 입금, 신용 청구 반영, 실적·혜택 한도 복원). "
                    + "바코드가 없으면 404 BARCODE_NOT_FOUND, 만료면 410 BARCODE_EXPIRED, 결제에 스캔·사용됐으면 409 BARCODE_ALREADY_USED. "
                    + "다른 고객의 바코드이거나 결제를 만든 POS가 아니면 404 RESOURCE_NOT_FOUND. "
                    + "취소가 끝나면 바코드를 닫고, 실패하면 그대로 두어 같은 바코드로 다시 시도할 수 있다. "
                    + "그 밖의 409(ALREADY_CANCELED·PAYMENT_NOT_CANCELABLE·IDEMPOTENCY_IN_PROGRESS)와 502는 앱 결제 취소와 같다. "
                    + "같은 Idempotency-Key로 다시 보내면 처음 응답을 돌려준다.")
    @PostMapping("/{paymentId}/cancel")
    ApiResponse<PosPaymentCancelResult> cancel(
            @RequestHeader(name = PosTerminalResolver.HEADER, required = false) String posKey,
            @RequestHeader(name = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @PathVariable Long paymentId,
            @Valid @RequestBody PosPaymentCancelRequest request) {
        return ApiResponse.ok(service.cancel(posKey, paymentId, request, idempotencyKey));
    }
}
