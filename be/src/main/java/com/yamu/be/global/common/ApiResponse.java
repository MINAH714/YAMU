package com.yamu.be.global.common;

import com.yamu.be.global.exception.ErrorCode;
import java.util.List;
import java.util.Map;

/**
 * 모든 API의 공통 응답. 형식은 API 명세서 1장 「응답 형식」을 따른다.
 *
 * <pre>
 * 성공: { "success": true,  "data": { ... }, "error": null }
 * 실패: { "success": false, "data": null,    "error": { "code": "INVALID_REQUEST", "message": "...", "details": { ... } } }
 * </pre>
 *
 * 성공/실패는 success로, 실패 원인은 error.code로, 4xx/5xx 구분은 HTTP 상태 코드로 나타낸다.
 * FE가 응답 모양을 한 가지로 다룰 수 있도록 data·error·details는 값이 없어도 null로 내려간다.
 */
public record ApiResponse<T>(
        boolean success,
        T data,
        ErrorResponse error) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null);
    }

    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(true, null, null);
    }

    public static ApiResponse<Void> fail(ErrorCode errorCode) {
        return fail(errorCode, errorCode.getMessage());
    }

    public static ApiResponse<Void> fail(ErrorCode errorCode, String message) {
        return fail(errorCode, message, null);
    }

    public static ApiResponse<Void> fail(ErrorCode errorCode, String message, Map<String, Object> details) {
        return new ApiResponse<>(false, null, new ErrorResponse(errorCode.getCode(), message, details));
    }

    /** 입력값 검사 실패. 필드별 사유는 error.details.fieldErrors에 담긴다. */
    public static ApiResponse<Void> fail(ErrorCode errorCode, List<FieldErrorResponse> fieldErrors) {
        return fail(errorCode, errorCode.getMessage(), Map.of("fieldErrors", fieldErrors));
    }

    /** 실패 정보. details는 코드마다 필요한 추가 정보(예: 잔액 부족 시 availableBalance)이며 없으면 null이다. */
    public record ErrorResponse(String code, String message, Map<String, Object> details) {
    }

    /** 입력값 검사 실패 시 어떤 값이 왜 틀렸는지. field는 요청 JSON의 필드 이름 또는 쿼리 파라미터 이름이다. */
    public record FieldErrorResponse(String field, String reason) {
    }
}
