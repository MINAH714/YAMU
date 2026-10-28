package com.yamu.be.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;

/** 인증 기록에 남기는 요청 정보. IP는 Nginx 뒤라면 프록시 주소일 수 있다(forward-headers 설정은 인프라와 맞춘다). */
public record ClientInfo(String ipAddress, String userAgent) {

    public static ClientInfo from(HttpServletRequest request) {
        return new ClientInfo(request.getRemoteAddr(), request.getHeader(HttpHeaders.USER_AGENT));
    }
}
