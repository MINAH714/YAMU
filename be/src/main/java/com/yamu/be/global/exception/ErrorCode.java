package com.yamu.be.global.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 전역 에러 코드.
 *
 * <p>이름 규칙은 {@code 도메인_상황}이다(예: CARD_NOT_FOUND, AUTH_TOKEN_EXPIRED).
 * 응답의 code 값은 enum 상수 이름을 그대로 쓴다. 코드 문자열을 따로 적지 않으므로
 * 상수 이름과 응답 코드가 어긋날 수 없다.
 *
 * <p>기능별 코드는 해당 기능을 만들 때 도메인별로 묶어 추가하고, API 명세서에도 함께 적는다.
 * FE가 code로 분기하므로 한 번 배포한 코드의 이름은 바꾸지 않는다.
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    /* 공통 */
    COMMON_INVALID_PARAM(HttpStatus.BAD_REQUEST, "잘못된 요청입니다."),
    COMMON_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 API를 찾을 수 없습니다."),
    COMMON_METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 HTTP 메서드입니다."),
    COMMON_UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 Content-Type입니다."),
    COMMON_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다.");

    private final HttpStatus status;
    private final String message;

    public String getCode() {
        return name();
    }
}
