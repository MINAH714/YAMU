package com.yamu.be.performance;

import com.yamu.be.global.common.ApiResponse;
import com.yamu.be.global.security.AuthUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.YearMonth;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "카드 실적")
@RestController
class PerformanceController {

    private final PerformanceService service;

    PerformanceController(PerformanceService service) {
        this.service = service;
    }

    @Operation(summary = "카드별 실적 현황",
            description = "등록된 카드(사용·추천 제외)의 그달 인정 실적, 목표 실적(카드 목록과 같은 기준), 남은 금액, 상태, "
                    + "최근 반영 결제. 상태는 NO_TARGET(실적 조건 없음)·INSUFFICIENT_DATA(실적 행 없음)·ACHIEVED·"
                    + "AT_RISK(지금 추세면 못 채움, 첫 5일 제외)·ON_TRACK 순으로 판정. 인정액 미확인 결제가 있으면 "
                    + "isProvisional=true. yearMonth가 없으면 이번 달.")
    @GetMapping("/api/users/me/card-performances")
    ApiResponse<CardPerformancesResponse> overview(@AuthenticationPrincipal AuthUser me,
            @Parameter(description = "한국 기준 실적 월(YYYY-MM), 2000-01~2100-12")
            @RequestParam(required = false) YearMonth yearMonth) {
        return ApiResponse.ok(service.overview(me.userId(), yearMonth));
    }

    @Operation(summary = "카드 실적 상세",
            description = "이번 달 실적 요약, 실적 구간별 새로 열리는 혜택·원화 월 한도 합·달성 여부, 다음 달 영향 문구. "
                    + "없는·남의·등록 해제 카드는 404 RESOURCE_NOT_FOUND.")
    @GetMapping("/api/user-cards/{userCardId}/performance")
    ApiResponse<PerformanceDetailResponse> detail(@AuthenticationPrincipal AuthUser me,
            @PathVariable long userCardId) {
        return ApiResponse.ok(service.detail(me.userId(), userCardId));
    }
}
