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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
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

    private static final Duration PAUSE = Duration.ofSeconds(30);

    private MockRestServiceServer server;
    private AiLivingCostClient client;
    private final MovableClock clock = new MovableClock(Instant.parse("2026-11-05T03:00:00Z"));
    private final Map<String, long[]> histories = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://ai.test");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new AiLivingCostClient(builder.build(), clock, PAUSE);
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
    void 시간_초과_뒤_30초_동안은_부르지_않고_지나면_다시_부른다() {
        server.expect(requestTo(URL)).andRespond(withException(new SocketTimeoutException("read timed out")));
        server.expect(requestTo(URL)).andRespond(withSuccess(
                "{\"horizonDays\":14,\"predictions\":[{\"key\":\"a0\",\"amount\":1},{\"key\":\"a1\",\"amount\":2}]}",
                MediaType.APPLICATION_JSON));

        assertThat(client.predict(AS_OF, 14, histories)).isEmpty();
        clock.advance(PAUSE.minusSeconds(1));
        // 멈춘 동안에는 요청을 보내지 않는다(두 번째 응답이 아직 쓰이지 않음)
        assertThat(client.predict(AS_OF, 14, histories)).isEmpty();
        clock.advance(Duration.ofSeconds(1));
        assertThat(client.predict(AS_OF, 14, histories)).contains(Map.of("a0", 1L, "a1", 2L));
        server.verify();
    }

    @Test
    void 서버_오류_5xx_뒤에도_30초_동안은_부르지_않는다() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(requestTo(URL)).andRespond(withSuccess(
                "{\"horizonDays\":14,\"predictions\":[{\"key\":\"a0\",\"amount\":1},{\"key\":\"a1\",\"amount\":2}]}",
                MediaType.APPLICATION_JSON));

        assertThat(client.predict(AS_OF, 14, histories)).isEmpty();
        assertThat(client.predict(AS_OF, 14, histories)).isEmpty();
        clock.advance(PAUSE);
        assertThat(client.predict(AS_OF, 14, histories)).isPresent();
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"bad-request", "not-json"})
    void 입력_오류_4xx나_읽을_수_없는_본문은_그_요청만_empty고_멈추지_않는다(String kind) {
        if (kind.equals("bad-request")) {
            server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.APPLICATION_JSON).body("{\"error\":\"INVALID_REQUEST\"}"));
        } else {
            server.expect(requestTo(URL)).andRespond(withSuccess("not json", MediaType.APPLICATION_JSON));
        }
        server.expect(requestTo(URL)).andRespond(withSuccess(
                "{\"horizonDays\":30,\"predictions\":[{\"key\":\"a0\",\"amount\":1},{\"key\":\"a1\",\"amount\":2}]}",
                MediaType.APPLICATION_JSON));

        assertThat(client.predict(AS_OF, 30, histories)).isEmpty();
        assertThat(client.predict(AS_OF, 30, histories)).isPresent();
        server.verify();
    }

    @Test
    void 계약_위반은_빠르게_끝나므로_멈추지_않는다() {
        server.expect(requestTo(URL)).andRespond(withSuccess("{\"horizonDays\":30}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(URL)).andRespond(withSuccess(
                "{\"horizonDays\":30,\"predictions\":[{\"key\":\"a0\",\"amount\":1},{\"key\":\"a1\",\"amount\":2}]}",
                MediaType.APPLICATION_JSON));

        assertThat(client.predict(AS_OF, 30, histories)).isEmpty();
        assertThat(client.predict(AS_OF, 30, histories)).isPresent();
        server.verify();
    }

    @Test
    void 예측이_하루_입력_상한_곱하기_일수를_넘으면_계약_위반이다() {
        long limit = AiLivingCostClient.MAX_DAILY_AMOUNT * 30;
        server.expect(requestTo(URL)).andRespond(withSuccess(
                "{\"horizonDays\":30,\"predictions\":[{\"key\":\"a0\",\"amount\":%d},{\"key\":\"a1\",\"amount\":0}]}"
                        .formatted(limit), MediaType.APPLICATION_JSON));
        server.expect(requestTo(URL)).andRespond(withSuccess(
                "{\"horizonDays\":30,\"predictions\":[{\"key\":\"a0\",\"amount\":%d},{\"key\":\"a1\",\"amount\":0}]}"
                        .formatted(limit + 1), MediaType.APPLICATION_JSON));

        assertThat(client.predict(AS_OF, 30, histories)).contains(Map.of("a0", limit, "a1", 0L));
        // 이보다 크면 뒤의 직선 잇기·하루 값 계산이 넘칠 수 있다
        assertThat(client.predict(AS_OF, 30, histories)).isEmpty();
    }

    @Test
    void 주소가_없거나_보낼_계좌가_없으면_부르지_않는다() {
        assertThat(AiLivingCostClient.disabled().enabled()).isFalse();
        assertThat(AiLivingCostClient.disabled().predict(AS_OF, 30, histories)).isEmpty();
        assertThat(client.predict(AS_OF, 30, Map.of())).isEmpty();
        server.verify();
    }

    /** 테스트에서 시간을 앞으로 옮기는 시계. */
    private static final class MovableClock extends Clock {
        private Instant now;

        MovableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
