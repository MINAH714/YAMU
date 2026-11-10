package com.yamu.be.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.yamu.be.global.common.ApiResponse;
import com.yamu.be.global.security.AuthUser;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 알림 설정 API. 명세: 노션 API 명세서 V2 「알림 설정 조회」「알림 설정 수정」, 모양은 앱 {@code MyModels.kt}. */
@RestController
@RequestMapping("/api/notifications/settings")
class NotificationSettingsController {

    private final NotificationSettingsService service;

    NotificationSettingsController(NotificationSettingsService service) {
        this.service = service;
    }

    @Operation(summary = "알림 설정 조회",
            description = "실적·잔고·거래(payment.result) 알림 켜기/끄기와 받는 시각(한국 시각 HH:mm). 저장한 적이 없으면 모두 켜짐·08:30. "
                    + "balance.alertThreshold는 잔고예보 설정의 안전 잔액이다. 보안 알림은 끌 수 없어 securityEnabled는 항상 true.")
    @GetMapping
    ApiResponse<NotificationSettingsResponse> get(@AuthenticationPrincipal AuthUser me) {
        return ApiResponse.ok(service.get(me.userId()));
    }

    /**
     * 보낸 항목만 바꾸고 바뀐 설정 전체를 돌려준다. 보내지 않은 항목과 null을 구분하려고 본문을 JSON 트리로 받는다.
     * 본문이 아예 없어도 서비스가 필드 오류(body)로 거절하도록 required=false로 받는다.
     */
    @Operation(summary = "알림 설정 수정",
            description = "예: {\"performance\":{\"enabled\":false}}, {\"performance\":{\"time\":\"21:00\"},\"balance\":{\"time\":\"21:00\"}}. "
                    + "바꿀 수 있는 항목: performance.enabled·time, balance.enabled·time, payment.result. "
                    + "balance.alertThreshold는 잔고예보 설정 수정에서 바꾼다(여기로 보내면 400).")
    @PatchMapping
    ApiResponse<NotificationSettingsResponse> update(@AuthenticationPrincipal AuthUser me,
            @RequestBody(required = false) JsonNode body) {
        return ApiResponse.ok(service.update(me.userId(), body));
    }
}
