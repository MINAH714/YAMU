package com.yamu.be.benefitlimit;

import com.yamu.be.global.common.ApiResponse;
import com.yamu.be.global.security.AuthUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "카드 실적")
@RestController
class BenefitLimitController {

    private final BenefitLimitService service;

    BenefitLimitController(BenefitLimitService service) {
        this.service = service;
    }

    @Operation(summary = "혜택 한도 현황",
            description = "등록된 카드(사용·추천 제외)의 이번 달 월 혜택 한도. 한도 그룹당 한 줄(공유 한도도 한 줄), 상태는 "
                    + "AVAILABLE·NEAR_END(남은 한도 ≤ 최대의 20%)·EXHAUSTED(0)·LOCKED(지난달 실적 미달, lockedReason). "
                    + "최대 한도는 지난달 실적 구간 기준, 사용량 행이 없으면 0. 금액 합계는 원화 한도만, 횟수·포인트 한도는 "
                    + "remainingAmount null. 건별 한도·한도 없는 혜택은 빠진다.")
    @GetMapping("/api/benefit-limits")
    ApiResponse<BenefitLimitsResponse> limits(@AuthenticationPrincipal AuthUser me) {
        return ApiResponse.ok(service.limits(me.userId()));
    }
}
