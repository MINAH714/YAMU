package com.yamu.be.forecast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.SocketTimeoutException;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class AiLivingCostClientTest {
    private static final LocalDate AS_OF = LocalDate.of(2026, 11, 4);
    private static final String URL = "http://ai.test/forecast/living-cost";

    private MockRestServiceServer server;
    private AiLivingCostClient client;
    private final Map<String, long[]> histories = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://ai.test");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new AiLivingCostClient(builder.build());
        long[] daily = new long[90];
        Arrays.fill(daily, 1_000);
        histories.put("a0", daily);
        histories.put("a1", new long[90]);
    }

    @Test
    void ai_README의_요청_모양으로_보내고_key별_예측을_읽는다() {
        server.expect(requestTo(URL)).andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {"asOf":"2026-11-04","horizonDays":30,
                         "accounts":[{"key":"a0","dailySpending":%s},{"key":"a1","dailySpending":%s}]}"""
                        .formatted(Arrays.toString(histories.get("a0")), Arrays.toString(histories.get("a1"))), true))
                .andRespond(withSuccess("""
                        {"modelVersion":"two-stage-tree","horizonDays":30,
                         "predictions":[{"key":"a1","amount":11162},{"key":"a0","amount":135834}]}""",
                        MediaType.APPLICATION_JSON));

        assertThat(client.predict(AS_OF, 30, histories)).contains(Map.of("a0", 135_834L, "a1", 11_162L));
        server.verify();
    }

    @Test
    void 시간_초과면_empty다() {
        server.expect(requestTo(URL)).andRespond(withException(new SocketTimeoutException("read timed out")));

        assertThat(client.predict(AS_OF, 14, histories)).isEmpty();
    }

    @Test
    void 모델이_없는_503과_서버_오류는_empty다() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_JSON).body("{\"error\":\"MODEL_MISSING\"}"));
        assertThat(client.predict(AS_OF, 14, histories)).isEmpty();

        server.reset();
        server.expect(requestTo(URL)).andRespond(withServerError());
        assertThat(client.predict(AS_OF, 14, histories)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // 예측 일수가 다름
            "{\"horizonDays\":14,\"predictions\":[{\"key\":\"a0\",\"amount\":1},{\"key\":\"a1\",\"amount\":2}]}",
            // key 하나 빠짐
            "{\"horizonDays\":30,\"predictions\":[{\"key\":\"a0\",\"amount\":1}]}",
            // 보내지 않은 key
            "{\"horizonDays\":30,\"predictions\":[{\"key\":\"a0\",\"amount\":1},{\"key\":\"zz\",\"amount\":2}]}",
            // key 중복
            "{\"horizonDays\":30,\"predictions\":[{\"key\":\"a0\",\"amount\":1},{\"key\":\"a0\",\"amount\":2}]}",
            // 음수
            "{\"horizonDays\":30,\"predictions\":[{\"key\":\"a0\",\"amount\":-1},{\"key\":\"a1\",\"amount\":2}]}",
            // 소수·문자열 금액
            "{\"horizonDays\":30,\"predictions\":[{\"key\":\"a0\",\"amount\":1.5},{\"key\":\"a1\",\"amount\":2}]}",
            "{\"horizonDays\":30,\"predictions\":[{\"key\":\"a0\",\"amount\":\"1\"},{\"key\":\"a1\",\"amount\":2}]}",
            // 예측 목록 없음
            "{\"horizonDays\":30}",
            // JSON이 아님
            "not json",
    })
    void 응답이_계약과_다르면_empty다(String body) {
        server.expect(requestTo(URL)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        assertThat(client.predict(AS_OF, 30, histories)).isEmpty();
    }

    @Test
    void 주소가_없거나_보낼_계좌가_없으면_부르지_않는다() {
        assertThat(AiLivingCostClient.disabled().enabled()).isFalse();
        assertThat(AiLivingCostClient.disabled().predict(AS_OF, 30, histories)).isEmpty();
        assertThat(client.predict(AS_OF, 30, Map.of())).isEmpty();
        server.verify();
    }
}
