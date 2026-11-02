package com.yamu.be.auth;

import com.yamu.be.auth.dto.DeviceListResponse;
import com.yamu.be.global.common.ApiResponse;
import com.yamu.be.global.security.AuthUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 로그인 기기 관리. 명세: 노션 API 명세서 V2 「등록 기기 목록 조회」「기기 해제」. Access Token으로만 부른다
 * (기기 등록 POST는 가입 진행 토큰도 받는 {@link CredentialSetupController}에 있다).
 */
@RestController
@RequestMapping("/api/auth/devices")
@RequiredArgsConstructor
public class DeviceController {

    private final DeviceManagementService deviceManagementService;

    @GetMapping
    public ApiResponse<DeviceListResponse> list(@AuthenticationPrincipal AuthUser me) {
        return ApiResponse.ok(deviceManagementService.list(me));
    }

    /** 204 No Content(명세). X-Step-Up-Token(APP_UNLOCK) 필요. */
    @DeleteMapping("/{userDeviceId}")
    public ResponseEntity<Void> revoke(@AuthenticationPrincipal AuthUser me, @PathVariable Long userDeviceId,
            @RequestHeader(value = StepUpService.HEADER, required = false) String stepUpToken) {
        deviceManagementService.revoke(me, userDeviceId, stepUpToken);
        return ResponseEntity.noContent().build();
    }
}
