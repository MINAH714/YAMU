package com.yamu.be.forecast;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI 생활비 예측 서버 설정({@code yamu.ai.*}, ai/README.md 「생활비 예측 API」).
 *
 * @param baseUrl        AI 서버 주소. 예: {@code http://localhost:8001}. 운영은 환경변수 {@code AI_BASE_URL}.
 *                       비어 있으면 AI를 부르지 않고 TSB로 계산한다(서버 시작 때 경고 로그 한 번)
 * @param connectTimeout 연결 제한 시간(2026-11-05 결정 1초)
 * @param readTimeout    응답 제한 시간(1초). 넘으면 그 요청 전체를 TSB로 계산한다
 */
@ConfigurationProperties(prefix = "yamu.ai")
record AiLivingCostProperties(String baseUrl, Duration connectTimeout, Duration readTimeout) {

    boolean hasBaseUrl() {
        return baseUrl != null && !baseUrl.isBlank();
    }
}
