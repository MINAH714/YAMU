package com.yamu.be.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 로그인 정책({@code yamu.auth.*}). 기본값은 노션 명세 기준이다.
 *
 * @param challengeTtl       로그인 challenge 유효 시간(60초)
 * @param refreshTokenTtl    Refresh Token 유효 시간(14일). 재발급할 때마다 다시 14일로 늘어난다
 * @param refreshReuseGrace  재발급 직후 직전 토큰이 다시 와도 탈취로 보지 않는 시간. 앱이 동시에 재발급을 두 번 부른 경우를 구분한다
 * @param maxFailedAttempts  연속 실패 한도(5회)
 * @param lockDuration       잠금 시간(30분)
 */
@ConfigurationProperties(prefix = "yamu.auth")
public record AuthProperties(
        Duration challengeTtl,
        Duration refreshTokenTtl,
        Duration refreshReuseGrace,
        int maxFailedAttempts,
        Duration lockDuration) {
}
