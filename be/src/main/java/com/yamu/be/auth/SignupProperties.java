package com.yamu.be.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 휴대폰 본인인증(연출)·가입 진행 정책({@code yamu.auth.signup.*}). 기본값은 노션 명세 기준이다.
 *
 * @param verificationTtl     인증번호 유효 시간(3분)
 * @param resendInterval      같은 번호로 다시 요청할 수 있는 간격(1분)
 * @param maxCodeAttempts     인증번호를 틀릴 수 있는 횟수(5회). 다 틀리면 요청부터 다시
 * @param tokenTtl            가입 진행 토큰(signupToken) 유효 시간(30분)
 * @param exposeDemoCode      응답에 인증번호(demoCode)를 내려줄지. 문자 발송이 없는 시연 서버라 켠다. 문자 서비스를 붙이면 끈다
 * @param memberIdAttempts    SSAFY 계정 ID가 겹칠 때(E4002) 새 ID로 다시 만들어 보는 최대 횟수
 */
@ConfigurationProperties(prefix = "yamu.auth.signup")
public record SignupProperties(
        Duration verificationTtl,
        Duration resendInterval,
        int maxCodeAttempts,
        Duration tokenTtl,
        boolean exposeDemoCode,
        int memberIdAttempts) {
}
