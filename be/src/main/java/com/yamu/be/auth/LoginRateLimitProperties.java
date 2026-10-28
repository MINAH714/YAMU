package com.yamu.be.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 로그인 준비·로그인 호출 횟수 제한({@code yamu.auth.rate-limit.*}). 두 API를 합쳐서 센다(로그인 한 번 = 요청 2번).
 *
 * @param window    세는 구간(1분). 구간이 끝나면 0부터 다시 센다
 * @param perDevice 같은 기기 번호로 구간 안에 허용하는 요청 수(10 = 로그인 5번)
 * @param perIp     같은 IP로 구간 안에 허용하는 요청 수(60). 시연장처럼 여러 명이 같은 와이파이를 쓰는 경우를 생각해 넉넉히 둔다
 */
@ConfigurationProperties(prefix = "yamu.auth.rate-limit")
public record LoginRateLimitProperties(Duration window, int perDevice, int perIp) {
}
