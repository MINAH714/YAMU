package com.yamu.be.global.exception;

import com.yamu.be.global.common.ApiResponse;
import com.yamu.be.global.common.ApiResponse.FieldErrorResponse;
import jakarta.persistence.OptimisticLockException;
import jakarta.validation.ConstraintViolationException;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.lang.Nullable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * 모든 예외를 공통 실패 응답({@link ApiResponse#fail})으로 바꾼다.
 *
 * <p>Spring MVC가 던지는 표준 예외(404 없는 경로, 405 메서드 불일치, 415 Content-Type 불일치,
 * 400 JSON 파싱 실패·파라미터 누락·타입 불일치 등)는 {@link ResponseEntityExceptionHandler}가
 * 알맞은 HTTP 상태로 분류해 준다. 여기서는 그 응답 본문만 공통 형식으로 바꾼다.
 * 상속하지 않고 {@code @ExceptionHandler(Exception.class)} 하나로 받으면 없는 경로 호출까지
 * 500 + 스택트레이스로 처리되어, 클라이언트 실수가 서버 장애처럼 보이고 로그에 진짜 오류가 묻힌다.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    /* 서비스가 던진 예상된 실패 → ErrorCode의 HTTP 상태 */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException e) {
        ErrorCode ec = e.getErrorCode();
        log.warn("BusinessException {}: {}", ec.getCode(), e.getMessage());
        return ResponseEntity.status(ec.getStatus()).body(ApiResponse.fail(ec, e.getMessage(), e.getDetails()));
    }

    /* @Valid @RequestBody 검사 실패 → 400, 필드별 사유 */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldErrorResponse> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new FieldErrorResponse(fe.getField(), fe.getDefaultMessage()))
                .toList();
        return invalidParam(ex, errors, headers, status, request);
    }

    /* @RequestParam·@PathVariable에 붙인 @Min·@NotBlank 등 검사 실패 → 400, 파라미터별 사유 */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldErrorResponse> errors = new ArrayList<>();
        ex.getParameterValidationResults().forEach(result -> {
            if (result instanceof ParameterErrors pe) {
                for (FieldError fe : pe.getFieldErrors()) {
                    errors.add(new FieldErrorResponse(fe.getField(), fe.getDefaultMessage()));
                }
                return;
            }
            String name = result.getMethodParameter().getParameterName();
            result.getResolvableErrors()
                    .forEach(err -> errors.add(new FieldErrorResponse(name, err.getDefaultMessage())));
        });
        return invalidParam(ex, errors, headers, status, request);
    }

    /* 클래스에 @Validated를 붙여 메서드 검증을 쓴 경우 → 400 */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(ConstraintViolationException e) {
        List<FieldErrorResponse> errors = e.getConstraintViolations().stream()
                .map(v -> {
                    String path = v.getPropertyPath().toString();
                    // "메서드명.파라미터명" 형태라 마지막 부분만 남긴다.
                    return new FieldErrorResponse(path.substring(path.lastIndexOf('.') + 1), v.getMessage());
                })
                .toList();
        log.warn("검증 실패: {}", errors);
        ErrorCode ec = ErrorCode.INVALID_REQUEST;
        return ResponseEntity.status(ec.getStatus()).body(ApiResponse.fail(ec, errors));
    }

    /*
     * 위에서 따로 다루지 않은 Spring MVC 표준 예외는 모두 여기로 모인다.
     * HTTP 상태는 부모가 정한 값을 그대로 쓰고, 본문만 공통 실패 응답으로 바꾼다.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, @Nullable Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        ErrorCode ec = toErrorCode(statusCode);
        if (statusCode.is5xxServerError()) {
            log.error("MVC 처리 중 서버 오류", ex);
        } else {
            log.warn("{} {}: {}", statusCode.value(), ex.getClass().getSimpleName(), ex.getMessage());
        }
        return super.handleExceptionInternal(ex, ApiResponse.fail(ec, messageOf(ex, ec)), headers, statusCode, request);
    }

    /*
     * 같은 행을 두 요청이 동시에 고쳐 @Version이 어긋났다 → 409 CONCURRENT_MODIFICATION(다시 시도하면 된다).
     * 처리하지 않으면 500으로 나가 서버 장애처럼 보인다. 서비스가 상황에 맞는 코드(예: ACCOUNT_ALREADY_LINKED)로 바꿨으면 그쪽이 우선이다.
     */
    @ExceptionHandler({ObjectOptimisticLockingFailureException.class, OptimisticLockException.class})
    public ResponseEntity<ApiResponse<Void>> handleOptimisticLock(Exception e) {
        // 어떤 행이 겹쳤는지 남긴다(엔티티 이름·ID만, 개인정보 없음). 지워진 행을 고치는 진짜 버그도 409로 보일 수 있어서다.
        if (e instanceof ObjectOptimisticLockingFailureException ex) {
            log.warn("동시 수정 충돌: entity={}, id={}", ex.getPersistentClassName(), ex.getIdentifier());
        } else {
            log.warn("동시 수정 충돌: {}", e.getClass().getSimpleName());
        }
        ErrorCode ec = ErrorCode.CONCURRENT_MODIFICATION;
        return ResponseEntity.status(ec.getStatus()).body(ApiResponse.fail(ec));
    }

    /* 그 외 예상하지 못한 예외 → 500. 원인은 로그에만 남기고 응답에는 노출하지 않는다. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e) {
        log.error("Unexpected exception", e);
        ErrorCode ec = ErrorCode.INTERNAL_ERROR;
        return ResponseEntity.status(ec.getStatus()).body(ApiResponse.fail(ec));
    }

    private ResponseEntity<Object> invalidParam(Exception ex, List<FieldErrorResponse> errors,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        log.warn("검증 실패: {}", errors);
        ApiResponse<Void> body = ApiResponse.fail(ErrorCode.INVALID_REQUEST, errors);
        return super.handleExceptionInternal(ex, body, headers, status, request);
    }

    /** 상태 코드만 아는 실패의 ErrorCode. {@link ApiErrorController}도 같은 기준을 쓴다 */
    static ErrorCode toErrorCode(HttpStatusCode status) {
        return switch (status.value()) {
            // MVC가 404를 내는 경우는 매핑된 API가 없을 때뿐이다. 데이터가 없는 404는 Service가 RESOURCE_NOT_FOUND로 던진다.
            case 404 -> ErrorCode.API_NOT_FOUND;
            case 405 -> ErrorCode.METHOD_NOT_ALLOWED;
            case 415 -> ErrorCode.UNSUPPORTED_MEDIA_TYPE;
            default -> status.is5xxServerError() ? ErrorCode.INTERNAL_ERROR : ErrorCode.INVALID_REQUEST;
        };
    }

    /* 클라이언트가 무엇을 고치면 되는지 알 수 있도록 400 계열은 원인별로 메시지를 구체화한다. */
    private static String messageOf(Exception ex, ErrorCode ec) {
        if (ex instanceof HttpMessageNotReadableException) {
            return "요청 본문(JSON) 형식이 올바르지 않습니다.";
        }
        if (ex instanceof MissingServletRequestParameterException e) {
            return "필수 파라미터 '" + e.getParameterName() + "'가 없습니다.";
        }
        if (ex instanceof TypeMismatchException e) {
            return "'" + e.getPropertyName() + "' 값의 형식이 올바르지 않습니다.";
        }
        return ec.getMessage();
    }
}
