package com.yamu.be.report;

import com.yamu.be.forecast.SpendingClassifier;
import com.yamu.be.global.common.ApiResponse;
import com.yamu.be.global.security.AuthUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.YearMonth;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "소비 리포트")
@RestController
@RequestMapping("/api/reports/monthly")
class ReportController {

    private final ReportService service;
    private final SpendingClassifier classifier;

    ReportController(ReportService service, SpendingClassifier classifier) {
        this.service = service;
        this.classifier = classifier;
    }

    @Operation(summary = "월간 소비 리포트",
            description = "한 달의 승인 결제 합계·생활 소비·지난달 같은 기간 생활 소비·업종별 금액·혜택 합계. 월은 한국 기준이고 "
                    + "결제 내역과 같이 POS 요청 시각으로 자른다. 취소·거절은 더하지 않는다. yearMonth가 없으면 이번 달.")
    @GetMapping
    ApiResponse<MonthlyReportResponse> monthly(@AuthenticationPrincipal AuthUser me,
            @Parameter(description = "한국 기준 월(YYYY-MM)") @RequestParam(required = false) YearMonth yearMonth) {
        // 생활 소비 합계가 비지 않게 읽기 직전에 미분류 승인 결제를 분류한다(결제 내역 목록과 같은 방식).
        // 분류는 UPDATE라 읽기 전용 트랜잭션인 service 밖(여기)에서 부른다
        classifier.classify(me.userId());
        return ApiResponse.ok(service.monthly(me.userId(), yearMonth));
    }

    @Operation(summary = "월간 혜택 리포트",
            description = "한 달의 예상·확정 혜택, 추천 1순위 대비 놓친 혜택(야무로 결제한 건만), 야무픽으로 더 받은 혜택, "
                    + "업종별 혜택. 확정 전 혜택이 있거나 이번 달이면 status는 PARTIAL. yearMonth가 없으면 지난달.")
    @GetMapping("/benefits")
    ApiResponse<BenefitReportResponse> benefits(@AuthenticationPrincipal AuthUser me,
            @Parameter(description = "한국 기준 월(YYYY-MM)") @RequestParam(required = false) YearMonth yearMonth) {
        return ApiResponse.ok(service.benefits(me.userId(), yearMonth));
    }
}
