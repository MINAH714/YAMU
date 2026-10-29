package com.yamu.be.global.finapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import java.net.http.HttpClient;
import java.time.Clock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 금융망 클라이언트 구성. JDK HttpClient를 쓰는 이유: 연결 시간 초과와 응답 시간 초과를 서로 다른 예외로 던져서
 * "요청이 안 갔다"와 "결과를 모른다"를 구분할 수 있다({@link FinApiClient}).
 *
 * <p>api-key가 비어 있어도 서버는 뜬다. 금융망을 쓰지 않는 작업(로컬 화면 개발, 테스트)을 막지 않기 위해서다.
 * 대신 금융망을 부르는 순간 실패하고, 시작할 때 경고를 남긴다.
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(FinApiProperties.class)
public class FinApiConfig {

    @Bean
    FinApiClient finApiClient(RestClient.Builder builder, FinApiProperties properties, ObjectMapper objectMapper,
            Clock clock, FinTransactionNoIssuer transactionNoIssuer, Validator validator) {
        if (!properties.hasApiKey()) {
            log.warn("yamu.fin-api.api-key가 비어 있습니다. 금융망 호출은 실패합니다(SSAFY_API_KEY 환경변수 또는 application-local.yml).");
        }
        // JDK의 응답 제한(request timeout)은 연결 시간까지 포함해서 잰다. 연결 제한이 같거나 길면 연결 실패가 응답 시간 초과로 먼저 끝나
        // "요청 안 감"을 "결과 불명"으로 잘못 보게 되므로 서버를 띄우지 않는다.
        if (properties.connectTimeout().compareTo(properties.readTimeout()) >= 0) {
            throw new IllegalStateException("yamu.fin-api.connect-timeout은 read-timeout보다 짧아야 합니다.");
        }
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        RestClient restClient = builder
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .build();
        return new FinApiClient(restClient, properties, objectMapper, clock, transactionNoIssuer, validator);
    }
}
