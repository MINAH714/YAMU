package com.yamu.be.global.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 전역 에러 코드.
 *
 * <p>이름은 API 명세서에 적힌 code를 그대로 쓴다. 공통 코드는 명세서 1장 「공통 에러 코드」,
 * 기능별 코드는 각 API 페이지의 에러 표에 있다. 명세서에 없는 코드가 필요하면 명세서에 먼저 적고 추가한다.
 * 응답의 code 값은 enum 상수 이름을 그대로 쓰므로 상수 이름과 응답 코드가 어긋날 수 없다.
 *
 * <p>FE가 code로 분기하므로 한 번 배포한 코드의 이름은 바꾸지 않는다.
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    /* 공통 (API 명세서 1장) */
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "잘못된 요청입니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "인증이 만료되었습니다."),
    STEP_UP_REQUIRED(HttpStatus.FORBIDDEN, "본인 인증이 필요합니다."),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 정보를 찾을 수 없습니다."),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.CONFLICT, "같은 요청 키로 다른 요청을 보낼 수 없습니다."),
    DATA_INSUFFICIENT(HttpStatus.UNPROCESSABLE_ENTITY, "계산에 필요한 데이터가 부족합니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다."),
    FIN_API_UNAVAILABLE(HttpStatus.BAD_GATEWAY, "금융망과 통신하지 못했습니다."),
    FIN_API_ERROR(HttpStatus.BAD_GATEWAY, "금융망에서 오류가 발생했습니다."),
    UPSTREAM_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "금융망 응답이 지연되고 있습니다."),

    /* 인증 - 로그인 준비·로그인·토큰 재발급 (API 명세 각 페이지) */
    DEVICE_NOT_REGISTERED(HttpStatus.UNAUTHORIZED, "등록되지 않았거나 해제된 기기입니다. 본인인증 후 기기를 다시 등록해 주세요."),
    DEVICE_LOCKED(HttpStatus.FORBIDDEN, "인증에 여러 번 실패해 기기가 잠겼습니다. 잠시 후 다시 시도해 주세요."),
    PIN_MISMATCH(HttpStatus.UNAUTHORIZED, "간편 비밀번호가 일치하지 않습니다."),
    SIGNATURE_INVALID(HttpStatus.UNAUTHORIZED, "기기 서명을 확인하지 못했습니다."),
    CHALLENGE_EXPIRED(HttpStatus.BAD_REQUEST, "인증 요청이 만료되었습니다. 처음부터 다시 시도해 주세요."),
    BIOMETRIC_NOT_ENABLED(HttpStatus.BAD_REQUEST, "이 기기는 생체 인증을 사용하지 않습니다."),
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "로그인이 만료되었습니다. 다시 로그인해 주세요."),

    /* 공통 - Spring MVC가 분류하는 요청 오류. 명세서 1장에 추가 필요 */
    API_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 API를 찾을 수 없습니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 HTTP 메서드입니다."),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 Content-Type입니다.");

    private final HttpStatus status;
    private final String message;

    public String getCode() {
        return name();
    }
}
