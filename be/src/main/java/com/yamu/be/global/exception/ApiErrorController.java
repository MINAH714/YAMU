package com.yamu.be.global.exception;

import com.yamu.be.global.common.ApiResponse;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 컨트롤러 밖(필터)에서 난 오류도 공통 실패 응답({@link ApiResponse#fail})으로 내보낸다(S15P31E103-369).
 *
 * <p>{@link GlobalExceptionHandler}는 컨트롤러 안의 예외만 받는다. 인증 필터가 토큰의 사용자·기기 상태를 DB에서 확인하다가
 * DB가 끊기면 예외가 필터 밖으로 나가고, 서블릿 컨테이너가 {@code /error}로 다시 보낸다. 이 클래스가 없으면 Spring Boot 기본
 * 응답({@code timestamp}·{@code status}·{@code error}·{@code path})이 나가 앱이 읽지 못한다. 이 빈이 있으면 기본
 * {@code BasicErrorController}는 만들어지지 않는다.
 *
 * <p>원인(예외 메시지·SQL)은 응답에 넣지 않는다. 스택은 서블릿 컨테이너가 이미 로그에 남긴다.
 */
@Slf4j
@RestController
public class ApiErrorController implements ErrorController {

    @RequestMapping("${server.error.path:${error.path:/error}}")
    public ResponseEntity<ApiResponse<Void>> error(HttpServletRequest request) {
        HttpStatusCode status = statusOf(request);
        ErrorCode ec = status.value() == HttpStatus.UNAUTHORIZED.value()
                ? ErrorCode.UNAUTHORIZED
                : GlobalExceptionHandler.toErrorCode(status);
        Object uri = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        Object ex = request.getAttribute(RequestDispatcher.ERROR_EXCEPTION);
        if (status.is5xxServerError()) {
            log.error("컨트롤러 밖 오류: status={}, uri={}, exception={}", status.value(), uri,
                    ex == null ? null : ex.getClass().getName());
        }
        return ResponseEntity.status(status).body(ApiResponse.fail(ec));
    }

    /** 컨테이너가 넣어 준 상태 코드. 없거나 이상하면 500 */
    private static HttpStatusCode statusOf(HttpServletRequest request) {
        Object code = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        if (code instanceof Integer value && value >= 400 && value <= 599) {
            return HttpStatusCode.valueOf(value);
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }
}
