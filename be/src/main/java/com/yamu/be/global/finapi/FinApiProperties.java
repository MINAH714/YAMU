package com.yamu.be.global.finapi;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * SSAFY 금융망 연결 설정({@code yamu.fin-api.*}).
 *
 * @param baseUrl        금융망 주소. 예: {@code https://finopenapi.ssafy.io/ssafy/api/v1}
 * @param apiKey         팀 공용 앱 키. 운영은 환경변수 {@code SSAFY_API_KEY}, 로컬은 application-local.yml. 로그·응답에 남기지 않는다
 * @param connectTimeout 연결 제한 시간(3초). 넘으면 요청이 가지 않은 것으로 본다
 * @param readTimeout    응답 제한 시간(10초). 넘으면 금융망이 처리했는지 알 수 없는 것으로 본다
 */
@ConfigurationProperties(prefix = "yamu.fin-api")
public record FinApiProperties(String baseUrl, String apiKey, Duration connectTimeout, Duration readTimeout) {

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }
}
