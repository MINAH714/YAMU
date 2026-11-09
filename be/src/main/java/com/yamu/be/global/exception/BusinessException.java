package com.yamu.be.global.exception;

import java.util.Map;
import lombok.Getter;

/**
 * 예상한 실패(없는 카드, 잔액 부족 등)를 알리는 예외.
 *
 * <p>Service에서 {@code throw new BusinessException(ErrorCode.INSUFFICIENT_BALANCE)}처럼 던지기만 하면
 * {@link GlobalExceptionHandler}가 ErrorCode의 HTTP 상태와 공통 실패 응답으로 바꾼다.
 * Controller·Service에서 try-catch로 실패 응답을 직접 만들지 않는다.
 */
@Getter
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    /** 응답의 error.details. API 명세서에 적힌 추가 정보가 없으면 null이다. */
    private final Map<String, Object> details;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.getMessage(), null);
    }

    /** 기본 메시지 대신 상황에 맞는 메시지를 내려야 할 때 쓴다. */
    public BusinessException(ErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    /** 명세서에 details가 정해진 실패일 때 쓴다(예: {@code Map.of("availableBalance", 8000)}). */
    public BusinessException(ErrorCode errorCode, Map<String, Object> details) {
        this(errorCode, errorCode.getMessage(), details);
    }

    public BusinessException(ErrorCode errorCode, String message, Map<String, Object> details) {
        super(message);
        this.errorCode = errorCode;
        this.details = details;
    }
}
