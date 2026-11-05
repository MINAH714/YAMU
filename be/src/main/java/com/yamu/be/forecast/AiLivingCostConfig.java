package com.yamu.be.forecast;

import java.net.http.HttpClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** AI 생활비 예측 클라이언트 구성. 주소가 없으면 부르지 않는 클라이언트를 만든다(잔고예보는 TSB로 계산). */
@Slf4j
@Configuration
@EnableConfigurationProperties(AiLivingCostProperties.class)
class AiLivingCostConfig {

    @Bean
    AiLivingCostClient aiLivingCostClient(RestClient.Builder builder, AiLivingCostProperties properties) {
        if (!properties.hasBaseUrl()) {
            log.warn("yamu.ai.base-url(AI_BASE_URL)이 비어 있어 AI 생활비 예측을 부르지 않습니다. 생활비는 TSB로 계산합니다.");
            return AiLivingCostClient.disabled();
        }
        // AI 서버(uvicorn)는 HTTP/1.1만 쓴다. h2c 업그레이드 요청을 보내지 않게 고정한다.
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        return new AiLivingCostClient(builder.baseUrl(properties.baseUrl()).requestFactory(requestFactory).build());
    }
}
