package com.yamu.be.assetflow;

import com.yamu.be.global.common.ApiResponse;
import com.yamu.be.global.security.AuthUser;
import io.swagger.v3.oas.annotations.Operation;
import java.time.YearMonth;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class AssetFlowController {
    private final AssetFlowService service;
    AssetFlowController(AssetFlowService service) { this.service = service; }

    @Operation(summary = "월별 자산 흐름", description = "과거 일말·오늘 현재·내일부터 30일 예보. 상세 계약: docs/be/asset-flow-api.md")
    @GetMapping("/api/asset-flows")
    ApiResponse<AssetFlowResponse> get(@AuthenticationPrincipal AuthUser me,
            @RequestParam(required = false) YearMonth yearMonth,
            @RequestParam(required = false) List<Long> accountIds,
            @RequestParam(defaultValue = "false") boolean refresh) {
        return ApiResponse.ok(service.get(me.userId(), yearMonth, accountIds, refresh));
    }
}
