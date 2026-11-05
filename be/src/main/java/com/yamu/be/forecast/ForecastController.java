package com.yamu.be.forecast;

import com.yamu.be.global.common.ApiResponse;
import com.yamu.be.global.security.AuthUser;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 잔고예보 조회 API. 명세: 노션 API 명세서 V2 「잔고예보 조회」. 명세와 다른 점은 docs/be/forecast.md 「명세와 다른 점」. */
@RestController
@RequestMapping("/api/forecasts")
class ForecastController {

    private final ForecastService service;

    ForecastController(ForecastService service) {
        this.service = service;
    }

    /**
     * 선택한 계좌의 날짜별 잔고예보. accountIds는 {@code accountIds=1&accountIds=2}나 {@code accountIds=1,2}로 보낸다.
     * until·days를 보내지 않으면 잔고예보 설정의 값으로 계산한다.
     */
    @GetMapping("/balance")
    ApiResponse<BalanceForecast> balance(@AuthenticationPrincipal AuthUser me,
            @RequestParam(required = false) List<Long> accountIds,
            @RequestParam(required = false) BalanceForecast.Until until,
            @RequestParam(required = false) Integer days,
            @RequestParam(defaultValue = "false") boolean includeAccountDetails,
            @RequestParam(defaultValue = "false") boolean refresh) {
        return ApiResponse.ok(service.forecast(me.userId(), accountIds, until, days, includeAccountDetails, refresh));
    }
}
