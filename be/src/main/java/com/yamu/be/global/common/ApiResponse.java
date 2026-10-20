package com.yamu.be.global.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.yamu.be.global.exception.ErrorCode;
import java.util.List;

/**
 * 모든 API의 공통 응답.
 *
 * <pre>
 * 성공: { "success": true, "data": { ... } }
 * 실패: { "success": false, "code": "COMMON_INVALID_PARAM", "message": "...", "errors": [ ... ] }
 * </pre>
 *
 * 성공/실패는 success로, 실패 원인은 code로, 4xx/5xx 구분은 HTTP 상태 코드로 나타낸다.
 * 값이 없는 필드(성공 시 code·message·errors, 실패 시 data)는 응답에서 빠진다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
        boolean success,
        T data,
        String code,
        String message,
        List<FieldErrorResponse> errors) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null, null, null);
    }

    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(true, null, null, null, null);
    }

    public static ApiResponse<Void> fail(ErrorCode errorCode) {
        return fail(errorCode, errorCode.getMessage());
    }

    public static ApiResponse<Void> fail(ErrorCode errorCode, String message) {
        return new ApiResponse<>(false, null, errorCode.getCode(), message, null);
    }

    public static ApiResponse<Void> fail(ErrorCode errorCode, List<FieldErrorResponse> errors) {
        return new ApiResponse<>(false, null, errorCode.getCode(), errorCode.getMessage(), errors);
    }

    /** 입력값 검사 실패 시 어떤 값이 왜 틀렸는지. field는 요청 JSON의 필드 이름 또는 쿼리 파라미터 이름이다. */
    public record FieldErrorResponse(String field, String reason) {
    }
}
