package com.yamu.be.payment.barcode;

import com.yamu.be.auth.StepUpService;
import com.yamu.be.global.common.ApiResponse;
import com.yamu.be.global.security.AuthUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 결제 바코드 발급·조회. API 명세 「결제 바코드 발급」「바코드 상태·결제 요청 조회」. */
@Tag(name = "결제 바코드")
@RestController
@RequestMapping("/api/payments/barcodes")
class BarcodeController {

    private final BarcodeService barcodes;

    BarcodeController(BarcodeService barcodes) {
        this.barcodes = barcodes;
    }

    @Operation(summary = "결제 바코드 발급", description = "1회용 통합 바코드·QR 토큰을 발급한다. 180초 동안 유효하다.")
    @PostMapping
    ResponseEntity<ApiResponse<BarcodeService.Issued>> issue(
            @AuthenticationPrincipal AuthUser me,
            @RequestHeader(name = StepUpService.HEADER, required = false) String stepUpToken) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(barcodes.issue(me, stepUpToken)));
    }

    @Operation(summary = "바코드 상태 조회", description = "앱이 폴링해 POS가 바코드를 스캔했는지 확인한다.")
    @GetMapping("/{barcodeToken}")
    ApiResponse<BarcodeService.State> get(@AuthenticationPrincipal AuthUser me, @PathVariable String barcodeToken) {
        return ApiResponse.ok(barcodes.get(me.userId(), barcodeToken));
    }
}
