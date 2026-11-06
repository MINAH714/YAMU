package com.yamu.be.forecast;

import com.yamu.be.global.common.ApiResponse;
import com.yamu.be.global.security.AuthUser;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 잔고예보·생활비 예측 상세 조회 API. 명세: 노션 API 명세서 V2 「잔고예보 조회」「생활비 예측 상세」.
 * 명세와 다른 점은 docs/be/forecast.md 「명세와 다른 점」.
 */
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

    /**
     * 선택한 계좌의 예상 생활비와 계산 근거(명세 「생활비 예측 상세」). accountIds는 잔고예보와 같은 방식으로 보낸다.
     * until·days는 명세에 없는 선택값이다. 잔고예보에 보낸 값을 그대로 보내면 같은 기간의 예상 생활비가 되고,
     * 보내지 않으면 잔고예보 설정을 따른다. 생활비 원천이 소비 분류를 쓰므로 읽기 전용 트랜잭션을 붙이지 않는다.
     */
    @GetMapping("/living-cost")
    ApiResponse<LivingCostForecast> livingCost(@AuthenticationPrincipal AuthUser me,
            @RequestParam(required = false) List<Long> accountIds,
            @RequestParam(required = false) BalanceForecast.Until until,
            @RequestParam(required = false) Integer days,
            @RequestParam(defaultValue = "false") boolean includeAccountDetails) {
        return ApiResponse.ok(service.livingCost(me.userId(), accountIds, until, days, includeAccountDetails));
    }
}
