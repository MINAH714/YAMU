package com.yamu.be.performance;

import com.yamu.be.global.common.ApiResponse;
import com.yamu.be.global.security.AuthUser;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 실적 달성 유불리 분석·판단 기록 API(S15P31E103-375). 명세: 노션 API 명세서 V2 「실적 달성 유불리 분석」「실적 판단 결정 기록」.
 * 경로는 앱이 부르는 {@code /api/user-cards/{id}/...}를 따른다(명세서는 {@code /api/cards/...}).
 */
@RestController
@RequestMapping("/api/user-cards/{userCardId}/performance")
class PerformanceAnalysisController {

    private final PerformanceAnalysisService analysis;
    private final PerformanceDecisionService decisions;

    PerformanceAnalysisController(PerformanceAnalysisService analysis, PerformanceDecisionService decisions) {
        this.analysis = analysis;
        this.decisions = decisions;
    }

    /** scenarioMoveItemIds: 이 카드로 옮긴다고 가정할 예정 지출 번호(쉼표 구분). 저장하지 않는다. */
    @GetMapping("/analysis")
    ApiResponse<PerformanceAnalysisResponse> analysis(@AuthenticationPrincipal AuthUser me,
            @PathVariable long userCardId,
            @RequestParam(required = false) String scenarioMoveItemIds) {
        return ApiResponse.ok(analysis.analyze(me.userId(), userCardId,
                PerformanceAnalysisService.parseScenario(scenarioMoveItemIds)));
    }

    @PostMapping("/decisions")
    ApiResponse<PerformanceDecisionResponse> decide(@AuthenticationPrincipal AuthUser me,
            @PathVariable long userCardId, @Valid @RequestBody PerformanceDecisionRequest request) {
        return ApiResponse.ok(decisions.decide(me.userId(), userCardId, PerformanceDecisionService.command(request)));
    }
}
