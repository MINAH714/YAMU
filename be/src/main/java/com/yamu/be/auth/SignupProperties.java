package com.yamu.be.auth;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 휴대폰 본인인증(연출)·가입 진행 정책({@code yamu.auth.signup.*}). 기본값은 노션 명세 기준이다.
 * 값이 빠졌거나 0 이하면 서버가 뜨지 않는다.
 *
 * @param verificationTtl     인증번호 유효 시간(3분)
 * @param resendInterval      같은 번호로 다시 요청할 수 있는 간격(1분)
 * @param maxCodeAttempts     인증번호를 틀릴 수 있는 횟수(5회). 다 틀리면 요청부터 다시
 * @param tokenTtl            가입 진행 토큰(signupToken) 유효 시간(30분)
 * @param exposeDemoCode      응답에 인증번호(demoCode)를 내려줄지. 문자 발송이 없는 시연 서버라 켠다. 문자 서비스를 붙이면 끈다
 * @param perIp               본인인증 요청·확인 두 API를 합쳐 같은 IP로 구간(yamu.auth.rate-limit.window) 안에 허용하는 요청 수
 */
@Validated
@ConfigurationProperties(prefix = "yamu.auth.signup")
public record SignupProperties(
        @NotNull Duration verificationTtl,
        @NotNull Duration resendInterval,
        @Min(1) int maxCodeAttempts,
        @NotNull Duration tokenTtl,
        boolean exposeDemoCode,
        @Min(1) int perIp) {
}
