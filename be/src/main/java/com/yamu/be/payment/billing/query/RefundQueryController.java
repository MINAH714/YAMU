package com.yamu.be.payment.billing.query;

import com.yamu.be.global.common.ApiResponse;
import com.yamu.be.global.common.PageResponse;
import com.yamu.be.global.security.AuthUser;
import com.yamu.be.payment.billing.RefundStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 환급 내역 조회 API. 명세: 노션 API 명세서 V2 「환급 내역 조회」. */
@RestController
@RequestMapping("/api/refunds")
class RefundQueryController {

    private final RefundQueryService service;

    RefundQueryController(RefundQueryService service) {
        this.service = service;
    }

    @Operation(summary = "환급 내역 조회",
            description = "신용카드 확정 후 취소 환급(CANCEL_AFTER_CONFIRM)과 체크카드 취소 환불(CHECK_CANCEL, refundId null)을 "
                    + "생성 시각 최신순으로. 기간은 생성일(한국 날짜, 포함)이고 기본은 오늘을 포함한 최근 3개월, 최대 12개월. "
                    + "무효(CANCELED) 환급은 금액 0, 기한이 아직 없는 PENDING 환급은 estimated=true(금액이 예상값). "
                    + "summary는 기간·상태와 상관없이 입금 전(PENDING) 환급의 금액·건수와 그중 예상값 몫이다.")
    @GetMapping
    ApiResponse<RefundListResponse> list(@AuthenticationPrincipal AuthUser me,
            @Parameter(description = "PENDING · COMPLETED · FAILED · CANCELED") @RequestParam(required = false)
            RefundStatus status,
            @Parameter(description = "한국 날짜, 포함. to 없이 오늘보다 늦으면 400") @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "한국 날짜, 포함. from부터 12개월 이하") @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "page는 1 이상입니다.") int page,
            @RequestParam(defaultValue = "" + PageResponse.DEFAULT_SIZE)
            @Min(value = 1, message = "size는 1 이상입니다.")
            @Max(value = PageResponse.MAX_SIZE, message = "size는 100 이하입니다.") int size) {
        return ApiResponse.ok(service.list(me.userId(), status, from, to, page, size));
    }
}
