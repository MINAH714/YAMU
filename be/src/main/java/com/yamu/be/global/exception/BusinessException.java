package com.yamu.be.global.exception;

import lombok.Getter;

/**
 * 예상한 실패(없는 카드, 중복 등록 등)를 알리는 예외.
 *
 * <p>Service에서 {@code throw new BusinessException(ErrorCode.CARD_NOT_FOUND)}처럼 던지기만 하면
 * {@link GlobalExceptionHandler}가 ErrorCode의 HTTP 상태와 공통 실패 응답으로 바꾼다.
 * Controller·Service에서 try-catch로 실패 응답을 직접 만들지 않는다.
 */
@Getter
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    /** 기본 메시지 대신 상황에 맞는 메시지를 내려야 할 때 쓴다. */
    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
