package com.yamu.be.forecast;

import com.fasterxml.jackson.databind.JsonNode;
import com.yamu.be.global.common.ApiResponse;
import com.yamu.be.global.security.AuthUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 잔고예보 설정 API. 명세: 노션 API 명세서 V2 「잔고예보 설정 조회」「잔고예보 설정 수정」. */
@RestController
@RequestMapping("/api/forecasts/settings")
class ForecastSettingsController {

    private final ForecastSettingsService service;

    ForecastSettingsController(ForecastSettingsService service) {
        this.service = service;
    }

    /** 내 잔고예보 설정. 저장한 적이 없으면 기본값이다. */
    @GetMapping
    ApiResponse<ForecastSettingsResponse> get(@AuthenticationPrincipal AuthUser me) {
        return ApiResponse.ok(service.get(me.userId()));
    }

    /** 보낸 필드만 바꾸고 바뀐 설정 전체를 돌려준다. 보내지 않은 필드와 null을 구분하려고 본문을 JSON 트리로 받는다. */
    @PatchMapping
    ApiResponse<ForecastSettingsResponse> update(@AuthenticationPrincipal AuthUser me, @RequestBody JsonNode body) {
        return ApiResponse.ok(service.update(me.userId(), body));
    }
}
