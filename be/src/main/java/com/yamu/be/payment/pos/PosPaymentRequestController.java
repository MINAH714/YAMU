package com.yamu.be.payment.pos;

import com.yamu.be.global.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 모의 POS가 부르는 결제 요청 API. 인증은 Bearer가 아니라 {@code X-POS-Key}다(API 명세 「POS 결제 요청 생성」). */
@Tag(name = "모의 POS")
@RestController
@RequestMapping("/api/pos/payment-requests")
class PosPaymentRequestController {

    static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    private final PosPaymentRequestService service;

    PosPaymentRequestController(PosPaymentRequestService service) {
        this.service = service;
    }

    @Operation(summary = "POS 결제 요청 생성 (바코드 스캔)",
            description = "POS가 읽은 바코드와 금액·결제 방식·개월 수로 결제 요청을 만들고 추천을 계산해 둔다. "
                    + "사용자는 confirmDeadline(60초)까지 앱에서 승인한다. 응답에 카드·추천 정보는 없다.")
    @PostMapping
    ResponseEntity<ApiResponse<PosPaymentRequestResponse>> create(
            @RequestHeader(name = PosTerminalResolver.HEADER, required = false) String posKey,
            @RequestHeader(name = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody PosPaymentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(service.create(posKey, request, idempotencyKey)));
    }
}
