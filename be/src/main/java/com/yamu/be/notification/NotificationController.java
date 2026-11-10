package com.yamu.be.notification;

import com.yamu.be.global.common.ApiResponse;
import com.yamu.be.global.common.PageResponse;
import com.yamu.be.global.security.AuthUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 알림 목록·읽음 처리 API. 명세: 노션 API 명세서 V2 「알림 목록 조회」. */
@RestController
@RequestMapping("/api/notifications")
class NotificationController {

    private final NotificationService service;

    NotificationController(NotificationService service) {
        this.service = service;
    }

    @Operation(summary = "알림 목록 조회",
            description = "내 앱 내 알림을 최신순으로. unreadCount는 필터와 상관없는 안 읽은 알림 수다. "
                    + "refType·refId는 누르면 갈 대상이며 대상이 내 것인지는 대상 API가 다시 확인한다.")
    @GetMapping
    ApiResponse<NotificationListResponse> list(@AuthenticationPrincipal AuthUser me,
            @Parameter(description = "알림 유형. 쉼표로 여러 개(예: PAYMENT_RESULT,BILL_DUE_SOON). 없으면 전체")
            @RequestParam(required = false) List<NotificationType> type,
            @Parameter(description = "true면 안 읽은 알림만") @RequestParam(defaultValue = "false") boolean unreadOnly,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "page는 1 이상입니다.") int page,
            @RequestParam(defaultValue = "" + PageResponse.DEFAULT_SIZE)
            @Min(value = 1, message = "size는 1 이상입니다.")
            @Max(value = PageResponse.MAX_SIZE, message = "size는 100 이하입니다.") int size) {
        return ApiResponse.ok(service.list(me.userId(), type, unreadOnly, page, size));
    }

    @Operation(summary = "알림 읽음 처리",
            description = "이미 읽은 알림은 바꾸지 않고 처음 읽은 시각을 돌려준다. 없거나 남의 알림은 404 RESOURCE_NOT_FOUND.")
    @PatchMapping("/{notificationId}/read")
    ApiResponse<NotificationService.ReadResponse> read(@AuthenticationPrincipal AuthUser me,
            @PathVariable long notificationId) {
        return ApiResponse.ok(service.read(me.userId(), notificationId));
    }

    @Operation(summary = "알림 모두 읽음 처리", description = "내 안 읽은 알림을 모두 읽음으로 바꾸고 바꾼 건수를 돌려준다.")
    @PatchMapping("/read-all")
    ApiResponse<NotificationService.ReadAllResponse> readAll(@AuthenticationPrincipal AuthUser me) {
        return ApiResponse.ok(service.readAll(me.userId()));
    }
}
