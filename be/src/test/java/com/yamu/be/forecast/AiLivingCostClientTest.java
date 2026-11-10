package com.yamu.be.forecast;

import static com.yamu.be.forecast.AiLivingCostClient.Endpoint.CREDIT_SPENDING;
import static com.yamu.be.forecast.AiLivingCostClient.Endpoint.LIVING_COST;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
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

        assertThat(client.predict(LIVING_COST, AS_OF, 30, histories).map(AiLivingCostClient.Answer::amounts)).contains(Map.of("a0", 135_834L, "a1", 11_162L));
        server.verify();
    }

    @Test
    void 시간_초과면_empty다() {
        server.expect(requestTo(URL)).andRespond(withException(new SocketTimeoutException("read timed out")));

        assertThat(client.predict(LIVING_COST, AS_OF, 14, histories)).isEmpty();
    }

    @Test
    void 모델이_없는_503과_서버_오류는_empty다() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_JSON).body("{\"error\":\"MODEL_MISSING\"}"));
        assertThat(client.predict(LIVING_COST, AS_OF, 14, histories)).isEmpty();

        server.reset();
        server.expect(requestTo(URL)).andRespond(withServerError());
        assertThat(client.predict(LIVING_COST, AS_OF, 14, histories)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // 예측 일수가 다름
            "{\"modelVersion\":\"m\",\"horizonDays\":14,\"predictions\":[{\"key\":\"a0\",\"amount\":1},{\"key\":\"a1\",\"amount\":2}]}",
            // key 하나 빠짐
            "{\"modelVersion\":\"m\",\"horizonDays\":30,\"predictions\":[{\"key\":\"a0\",\"amount\":1}]}",
            // 보내지 않은 key
            "{\"modelVersion\":\"m\",\"horizonDays\":30,\"predictions\":[{\"key\":\"a0\",\"amount\":1},{\"key\":\"zz\",\"amount\":2}]}",
            // key 중복
            "{\"modelVersion\":\"m\",\"horizonDays\":30,\"predictions\":[{\"key\":\"a0\",\"amount\":1},{\"key\":\"a0\",\"amount\":2}]}",
            // 음수
            "{\"modelVersion\":\"m\",\"horizonDays\":30,\"predictions\":[{\"key\":\"a0\",\"amount\":-1},{\"key\":\"a1\",\"amount\":2}]}",
            // 소수·문자열 금액
            "{\"modelVersion\":\"m\",\"horizonDays\":30,\"predictions\":[{\"key\":\"a0\",\"amount\":1.5},{\"key\":\"a1\",\"amount\":2}]}",
            "{\"modelVersion\":\"m\",\"horizonDays\":30,\"predictions\":[{\"key\":\"a0\",\"amount\":\"1\"},{\"key\":\"a1\",\"amount\":2}]}",
            // 예측 목록 없음
            "{\"modelVersion\":\"m\",\"horizonDays\":30}",
            // JSON이 아님
            "not json",
            // 모델 버전 없음·빈 문자열·숫자(예측 이력에 남길 값, -405)
            "{\"horizonDays\":30,\"predictions\":[{\"key\":\"a0\",\"amount\":1},{\"key\":\"a1\",\"amount\":2}]}",
            "{\"modelVersion\":\" \",\"horizonDays\":30,\"predictions\":[{\"key\":\"a0\",\"amount\":1},{\"key\":\"a1\",\"amount\":2}]}",
            "{\"modelVersion\":7,\"horizonDays\":30,\"predictions\":[{\"key\":\"a0\",\"amount\":1},{\"key\":\"a1\",\"amount\":2}]}",
    })
    void 응답이_계약과_다르면_empty다(String body) {
        server.expect(requestTo(URL)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        assertThat(client.predict(LIVING_COST, AS_OF, 30, histories)).isEmpty();
    }

    @Test
    void 시간_초과_뒤_30초_동안은_부르지_않고_지나면_다시_부른다() {
        server.expect(requestTo(URL)).andRespond(withException(new SocketTimeoutException("read timed out")));
        server.expect(requestTo(URL)).andRespond(withSuccess(
                "{\"modelVersion\":\"m\",\"horizonDays\":14,\"predictions\":[{\"key\":\"a0\",\"amount\":1},{\"key\":\"a1\",\"amount\":2}]}",
                MediaType.APPLICATION_JSON));

        assertThat(client.predict(LIVING_COST, AS_OF, 14, histories)).isEmpty();
        clock.advance(PAUSE.minusSeconds(1));
        // 멈춘 동안에는 요청을 보내지 않는다(두 번째 응답이 아직 쓰이지 않음)
        assertThat(client.predict(LIVING_COST, AS_OF, 14, histories)).isEmpty();
        clock.advance(Duration.ofSeconds(1));
        assertThat(client.predict(LIVING_COST, AS_OF, 14, histories).map(AiLivingCostClient.Answer::amounts)).contains(Map.of("a0", 1L, "a1", 2L));
        server.verify();
    }

    @Test
    void 서버_오류_5xx_뒤에도_30초_동안은_부르지_않는다() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(requestTo(URL)).andRespond(withSuccess(
                "{\"modelVersion\":\"m\",\"horizonDays\":14,\"predictions\":[{\"key\":\"a0\",\"amount\":1},{\"key\":\"a1\",\"amount\":2}]}",
                MediaType.APPLICATION_JSON));

        assertThat(client.predict(LIVING_COST, AS_OF, 14, histories)).isEmpty();
        assertThat(client.predict(LIVING_COST, AS_OF, 14, histories)).isEmpty();
        clock.advance(PAUSE);
        assertThat(client.predict(LIVING_COST, AS_OF, 14, histories)).isPresent();
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
                "{\"modelVersion\":\"m\",\"horizonDays\":30,\"predictions\":[{\"key\":\"a0\",\"amount\":1},{\"key\":\"a1\",\"amount\":2}]}",
                MediaType.APPLICATION_JSON));

        assertThat(client.predict(LIVING_COST, AS_OF, 30, histories)).isEmpty();
        assertThat(client.predict(LIVING_COST, AS_OF, 30, histories)).isPresent();
        server.verify();
    }

    @Test
    void 계약_위반은_빠르게_끝나므로_멈추지_않는다() {
        server.expect(requestTo(URL)).andRespond(withSuccess("{\"modelVersion\":\"m\",\"horizonDays\":30}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(URL)).andRespond(withSuccess(
                "{\"modelVersion\":\"m\",\"horizonDays\":30,\"predictions\":[{\"key\":\"a0\",\"amount\":1},{\"key\":\"a1\",\"amount\":2}]}",
                MediaType.APPLICATION_JSON));

        assertThat(client.predict(LIVING_COST, AS_OF, 30, histories)).isEmpty();
        assertThat(client.predict(LIVING_COST, AS_OF, 30, histories)).isPresent();
        server.verify();
    }

    @Test
    void 예측이_하루_입력_상한_곱하기_일수를_넘으면_계약_위반이다() {
        long limit = AiLivingCostClient.MAX_DAILY_AMOUNT * 30;
        server.expect(requestTo(URL)).andRespond(withSuccess(
                "{\"modelVersion\":\"m\",\"horizonDays\":30,\"predictions\":[{\"key\":\"a0\",\"amount\":%d},{\"key\":\"a1\",\"amount\":0}]}"
                        .formatted(limit), MediaType.APPLICATION_JSON));
        server.expect(requestTo(URL)).andRespond(withSuccess(
                "{\"modelVersion\":\"m\",\"horizonDays\":30,\"predictions\":[{\"key\":\"a0\",\"amount\":%d},{\"key\":\"a1\",\"amount\":0}]}"
                        .formatted(limit + 1), MediaType.APPLICATION_JSON));

        assertThat(client.predict(LIVING_COST, AS_OF, 30, histories).map(AiLivingCostClient.Answer::amounts)).contains(Map.of("a0", limit, "a1", 0L));
        // 이보다 크면 뒤의 직선 잇기·하루 값 계산이 넘칠 수 있다
        assertThat(client.predict(LIVING_COST, AS_OF, 30, histories)).isEmpty();
    }

    @Test
    void 주소가_없거나_보낼_계좌가_없으면_부르지_않는다() {
        assertThat(AiLivingCostClient.disabled().enabled()).isFalse();
        assertThat(AiLivingCostClient.disabled().predict(LIVING_COST, AS_OF, 30, histories)).isEmpty();
        assertThat(client.predict(LIVING_COST, AS_OF, 30, Map.of())).isEmpty();
        server.verify();
    }

    @Test
    void 신용_생활_소비는_credit_spending_API로_같은_모양을_보낸다() {
        // S15P31E103-405: 신용카드 마감 전 사용 추정. 요청·응답 모양은 생활비 예측과 같다(ai/README.md)
        server.expect(requestTo("http://ai.test/forecast/credit-spending")).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.horizonDays").value(14)).andExpect(jsonPath("$.accounts.length()").value(2))
                .andRespond(withSuccess("""
                        {"modelVersion":"two-stage-tree-credit","horizonDays":14,
                         "predictions":[{"key":"a0","amount":70888},{"key":"a1","amount":12368}]}""",
                        MediaType.APPLICATION_JSON));

        assertThat(client.predict(CREDIT_SPENDING, AS_OF, 14, histories).map(AiLivingCostClient.Answer::amounts))
                .contains(Map.of("a0", 70_888L, "a1", 12_368L));
        server.verify();
    }

    @Test
    void key가_20개를_넘으면_20개씩_나눠_부르고_합친다() {
        // AI 서버는 한 요청에 20개까지 받는다(넘으면 400). 21개면 20개 + 1개 두 번(-404 Codex 검토, -405)
        Map<String, long[]> many = manyKeys(21);
        server.expect(requestTo(URL)).andExpect(jsonPath("$.accounts.length()").value(20))
                .andRespond(withSuccess(answer(0, 20), MediaType.APPLICATION_JSON));
        server.expect(requestTo(URL)).andExpect(jsonPath("$.accounts.length()").value(1))
                .andExpect(jsonPath("$.accounts[0].key").value("k20"))
                .andRespond(withSuccess(answer(20, 21), MediaType.APPLICATION_JSON));

        Map<String, Long> result = client.predict(LIVING_COST, AS_OF, 7, many).orElseThrow().amounts();

        assertThat(result).hasSize(21).containsEntry("k0", 0L).containsEntry("k20", 20L);
        server.verify();
    }

    @Test
    void 나눠_부른_요청이_하나라도_실패하면_전체가_empty다() {
        // 일부 key만 AI 값이고 나머지가 TSB인 섞인 결과를 만들지 않는다(부르는 쪽이 요청 전체를 TSB로 계산)
        server.expect(requestTo(URL)).andRespond(withSuccess(answer(0, 20), MediaType.APPLICATION_JSON));
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertThat(client.predict(LIVING_COST, AS_OF, 7, manyKeys(21))).isEmpty();
        server.verify();
    }

    @Test
    void 응답의_모델_버전을_금액과_함께_돌려준다() {
        server.expect(requestTo(URL)).andRespond(withSuccess(
                "{\"modelVersion\":\"two-stage-tree-20261111\",\"horizonDays\":7,\"predictions\":[{\"key\":\"a0\",\"amount\":1},{\"key\":\"a1\",\"amount\":2}]}",
                MediaType.APPLICATION_JSON));

        assertThat(client.predict(LIVING_COST, AS_OF, 7, histories)).contains(
                new AiLivingCostClient.Answer("two-stage-tree-20261111", Map.of("a0", 1L, "a1", 2L)));
    }

    @Test
    void 나눠_부른_요청끼리_모델_버전이_다르면_empty다() {
        // 나눠 부르는 사이 모델이 바뀌면 한 요청 안에 두 버전이 섞인다
        server.expect(requestTo(URL)).andRespond(withSuccess(answer(0, 20), MediaType.APPLICATION_JSON));
        server.expect(requestTo(URL)).andRespond(withSuccess(answer(20, 21).replace("\"m\"", "\"m2\""),
                MediaType.APPLICATION_JSON));

        assertThat(client.predict(LIVING_COST, AS_OF, 7, manyKeys(21))).isEmpty();
        server.verify();
    }

    @Test
    void 신용_API의_5xx는_신용만_멈추고_체크_생활비는_계속_부른다() {
        // 신용 모델만 못 읽은 AI 서버는 신용 API만 503이다(ai/README.md). 체크 생활비 AI까지 30초 TSB가 되지 않게 한다(-405 Codex 검토)
        server.expect(requestTo("http://ai.test/forecast/credit-spending"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(requestTo(URL)).andRespond(withSuccess(
                "{\"modelVersion\":\"m\",\"horizonDays\":14,\"predictions\":[{\"key\":\"a0\",\"amount\":1},{\"key\":\"a1\",\"amount\":2}]}",
                MediaType.APPLICATION_JSON));

        assertThat(client.predict(CREDIT_SPENDING, AS_OF, 14, histories)).isEmpty();
        assertThat(client.predict(LIVING_COST, AS_OF, 14, histories)).isPresent();
        // 신용은 멈춘 동안 요청을 보내지 않는다(더 기대한 요청이 없어 보내면 실패)
        assertThat(client.predict(CREDIT_SPENDING, AS_OF, 14, histories)).isEmpty();
        server.verify();
    }

    @Test
    void 시간_초과는_서버_장애라_두_API_모두_멈춘다() {
        server.expect(requestTo("http://ai.test/forecast/credit-spending"))
                .andRespond(withException(new SocketTimeoutException("read timed out")));

        assertThat(client.predict(CREDIT_SPENDING, AS_OF, 14, histories)).isEmpty();
        // 멈춘 동안에는 체크 생활비도 요청을 보내지 않는다(더 기대한 요청이 없어 보내면 실패)
        assertThat(client.predict(LIVING_COST, AS_OF, 14, histories)).isEmpty();
        server.verify();
    }

    /** k0 … k{n-1}, 모두 90일 0원. */
    private static Map<String, long[]> manyKeys(int n) {
        Map<String, long[]> out = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            out.put("k" + i, new long[90]);
        }
        return out;
    }

    /** k{from} … k{to-1}의 7일 예측. 금액은 번호와 같다. */
    private static String answer(int from, int to) {
        StringBuilder predictions = new StringBuilder();
        for (int i = from; i < to; i++) {
            predictions.append(i == from ? "" : ",").append("{\"key\":\"k").append(i).append("\",\"amount\":")
                    .append(i).append("}");
        }
        return "{\"modelVersion\":\"m\",\"horizonDays\":7,\"predictions\":[" + predictions + "]}";
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
