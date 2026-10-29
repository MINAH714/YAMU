package com.yamu.be.auth;

import com.yamu.be.auth.dto.DeviceRegisterRequest;
import com.yamu.be.auth.dto.DeviceRegisterResponse;
import com.yamu.be.auth.dto.PinSetupRequest;
import com.yamu.be.global.common.ApiResponse;
import com.yamu.be.global.security.AuthUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 간편 비밀번호 설정·변경과 기기 등록. 명세: 노션 API 명세서 V2 「간편 비밀번호 설정·변경」「기기 등록」.
 *
 * <p>가입·기기 재등록 중에는 가입 진행 토큰, 가입 후에는 Access Token을 {@code Authorization: Bearer}로 받는다.
 * 두 가지를 다 받으려고 SecurityConfig에서 이 경로는 인증 없이 열고, 가입 진행 토큰은 JWT 검사에서 뺀다(계좌 연결과 같은 방식).
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class CredentialSetupController {

    private final PinService pinService;
    private final DeviceRegistrationService deviceRegistrationService;
    private final SetupActorResolver actorResolver;

    /** 204 No Content(명세). */
    @PostMapping("/pin")
    public ResponseEntity<Void> setPin(@AuthenticationPrincipal AuthUser me,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = StepUpService.HEADER, required = false) String stepUpToken,
            @RequestBody PinSetupRequest request) {
        pinService.setPin(actorResolver.resolve(me, authorization), request.pin(), stepUpToken);
        return ResponseEntity.noContent().build();
    }

    /** 201 Created(명세). */
    @PostMapping("/devices")
    public ResponseEntity<ApiResponse<DeviceRegisterResponse>> registerDevice(@AuthenticationPrincipal AuthUser me,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = StepUpService.HEADER, required = false) String stepUpToken,
            @Valid @RequestBody DeviceRegisterRequest request) {
        DeviceRegisterResponse registered = deviceRegistrationService.register(
                actorResolver.resolve(me, authorization), request, stepUpToken);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(registered));
    }
}
