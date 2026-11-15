package com.yamu.be.global.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yamu.be.global.common.ApiResponse;
import com.yamu.be.global.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * 인증 실패를 공통 실패 응답으로 내려준다. 인증은 컨트롤러 앞 Security 필터에서 실패하므로
 * {@code GlobalExceptionHandler}가 아니라 여기서 응답을 직접 쓴다.
 *
 * <ul>
 *   <li>Access Token 만료 → 401 {@code TOKEN_EXPIRED}: 앱은 /api/auth/refresh로 재발급한다.</li>
 *   <li>해제된 기기(다른 폰에서 재등록 등)의 토큰 → 401 {@code DEVICE_NOT_REGISTERED}: 앱은 기기 재등록으로 안내한다
 *       (S15P31E103-351). 로그인·재발급의 해제된 기기 응답과 같은 코드다.</li>
 *   <li>토큰 없음·위조·형식 오류·탈퇴한 사용자·로그아웃한 기기 → 401 {@code UNAUTHORIZED}: 앱은 다시 로그인한다.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class JsonAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
            throws IOException {
        ErrorCode errorCode = isExpired(authException) ? ErrorCode.TOKEN_EXPIRED
                : isRevokedDevice(authException) ? ErrorCode.DEVICE_NOT_REGISTERED : ErrorCode.UNAUTHORIZED;
        response.setStatus(errorCode.getStatus().value());
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), ApiResponse.fail(errorCode));
    }

    private static boolean isRevokedDevice(AuthenticationException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof RevokedDeviceException) {
                return true;
            }
        }
        return false;
    }

    /** Resource Server는 만료를 원인 예외 JwtValidationException의 오류 설명("Jwt expired at ...")으로 알려 준다. */
    private static boolean isExpired(AuthenticationException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof JwtValidationException jve) {
                return jve.getErrors().stream()
                        .anyMatch(err -> err.getDescription() != null && err.getDescription().contains("expired"));
            }
        }
        return false;
    }
}
