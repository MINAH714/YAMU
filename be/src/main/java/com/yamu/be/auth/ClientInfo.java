package com.yamu.be.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;

/**
 * 인증 기록과 호출 횟수 제한에 쓰는 요청 정보.
 * IP는 {@code server.forward-headers-strategy: native}로 Nginx가 넘긴 X-Forwarded-For의 실제 접속 주소다(내부망 프록시가 보낸 헤더만 믿는다).
 */
public record ClientInfo(String ipAddress, String userAgent) {

    public static ClientInfo from(HttpServletRequest request) {
        return new ClientInfo(request.getRemoteAddr(), request.getHeader(HttpHeaders.USER_AGENT));
    }
}
