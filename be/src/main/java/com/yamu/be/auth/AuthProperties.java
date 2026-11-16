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
 * @param stepUpTokenTtl     본인 인증 토큰(stepUpToken) 유효 시간(5분). 한 번 쓰면 바로 지워진다
 * @param barcodeReissueWindow 결제 코드를 처음 인증해 연 뒤 다시 인증하지 않고 새 코드를 받을 수 있는 시간(임시 10분, S15P31E103-530).
 *                             설정이 없으면 0이라 재발급하지 않고 늘 다시 인증한다
 */
@ConfigurationProperties(prefix = "yamu.auth")
public record AuthProperties(
        Duration challengeTtl,
        Duration refreshTokenTtl,
        Duration refreshReuseGrace,
        int maxFailedAttempts,
        Duration lockDuration,
        Duration stepUpTokenTtl,
        Duration barcodeReissueWindow) {

    public AuthProperties {
        barcodeReissueWindow = barcodeReissueWindow == null ? Duration.ZERO : barcodeReissueWindow;
    }
}
