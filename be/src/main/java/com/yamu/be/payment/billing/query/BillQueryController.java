package com.yamu.be.payment.billing.query;

import com.yamu.be.global.common.ApiResponse;
import com.yamu.be.global.security.AuthUser;
import com.yamu.be.payment.billing.BillStatus;
import io.swagger.v3.oas.annotations.Operation;
import java.time.YearMonth;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 신용카드 청구서 조회 API. 명세: 노션 API 명세서 V2 「청구서 목록」·「청구서 상세」. */
@RestController
@RequestMapping("/api/billings")
class BillQueryController {

    private final BillQueryService service;

    BillQueryController(BillQueryService service) {
        this.service = service;
    }

    @Operation(summary = "청구서 목록",
            description = "신용카드별 월 청구서. billingMonth(YYYY-MM, 결제일이 속한 달)를 생략하면 끝나지 않은 청구서(ACCUMULATING·CONFIRMED·UNPAID) "
                    + "전부와 한국 기준 이번 달을 포함한 최근 3개월 청구서. 없는 카드·남의 카드는 404 RESOURCE_NOT_FOUND, 체크카드는 빈 목록. "
                    + "summary는 상태·월 필터와 상관없이 끝나지 않은 청구서로 계산한다.")
    @GetMapping
    ApiResponse<BillListResponse> list(@AuthenticationPrincipal AuthUser me,
            @RequestParam(required = false) Long userCardId,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth billingMonth,
            @RequestParam(required = false) BillStatus status) {
        return ApiResponse.ok(service.list(me.userId(), userCardId, billingMonth, status));
    }

    @Operation(summary = "청구서 상세",
            description = "청구서 한 건의 회차(BILL_ITEM)·청구할인·출금 결과·환급. 없는 청구서·남의 청구서는 404 RESOURCE_NOT_FOUND.")
    @GetMapping("/{billId}")
    ApiResponse<BillDetailResponse> detail(@AuthenticationPrincipal AuthUser me, @PathVariable Long billId) {
        return ApiResponse.ok(service.detail(me.userId(), billId));
    }
}
