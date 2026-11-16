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

    @Operation(summary = "결제 바코드 발급", description = "1회용 통합 바코드·QR 토큰을 발급한다. 60초 동안 유효하다(가정값, DEC-003).")
    @PostMapping
    ResponseEntity<ApiResponse<BarcodeService.Issued>> issue(
            @AuthenticationPrincipal AuthUser me,
            @RequestHeader(name = StepUpService.HEADER, required = false) String stepUpToken) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(barcodes.issue(me, stepUpToken)));
    }

    @Operation(summary = "결제 바코드 재발급", description = """
            결제 화면의 [새 코드]. 본인 인증 토큰 없이 이전 코드를 닫고 새 코드를 발급한다(임시 정책, S15P31E103-530).
            같은 기기에서 처음 인증한 지 10분 안이고, 이전 코드가 내 현재 코드이며 스캔·결제에 쓰이지 않았을 때만 된다(시간 만료는 허용).
            조건이 안 맞으면 403 STEP_UP_REQUIRED이고 앱은 본인 인증 뒤 발급 API를 부른다.
            같은 코드로 다시 부르면 앞서 발급한 새 코드를 돌려준다.""")
    @PostMapping("/{barcodeToken}/reissue")
    ResponseEntity<ApiResponse<BarcodeService.Issued>> reissue(
            @AuthenticationPrincipal AuthUser me, @PathVariable String barcodeToken) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(barcodes.reissue(me, barcodeToken)));
    }

    @Operation(summary = "바코드 상태 조회", description = "앱이 폴링해 POS가 바코드를 스캔했는지 확인한다.")
    @GetMapping("/{barcodeToken}")
    ApiResponse<BarcodeService.State> get(@AuthenticationPrincipal AuthUser me, @PathVariable String barcodeToken) {
        return ApiResponse.ok(barcodes.get(me.userId(), barcodeToken));
    }
}
