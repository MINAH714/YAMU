package com.yamu.be.forecast;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * AI 서버의 생활비 예측({@code POST /forecast/living-cost}, ai/README.md)을 부른다. 내부 전용 API라 BE 공통 응답 형식이 아니다.
 *
 * <p>응답을 믿지 않는다. 200이 아니거나, 시간 제한을 넘거나, 계약(예측 일수 일치, 보낸 key마다 0 이상 정수 하나)을 어기면
 * empty를 돌려주고 부르는 쪽이 그 요청 전체를 TSB로 계산한다(2026-11-05 결정: 계약 검사만, 값의 크기로 거르지 않음).
 * 요청·응답 값은 로그에 남기지 않는다(계좌 수·예측 일수·실패 종류만).
 */
@Slf4j
class AiLivingCostClient {
    private final RestClient restClient;

    /** @param restClient AI 서버 주소가 정해진 클라이언트. null이면 AI를 부르지 않는다(주소 미설정) */
    AiLivingCostClient(RestClient restClient) {
        this.restClient = restClient;
    }

    /** AI 서버 주소가 없을 때. 늘 empty를 돌려준다. */
    static AiLivingCostClient disabled() {
        return new AiLivingCostClient(null);
    }

    boolean enabled() {
        return restClient != null;
    }

    /**
     * 계좌마다 다음 horizonDays일(asOf 다음 날부터) 체크카드 생활 소비 합계를 예측한다.
     *
     * @param asOf        기록의 마지막 날(한국 날짜)
     * @param horizonDays 14 또는 30
     * @param histories   key(계좌번호·사용자 ID가 아닌 임의 값) → asOf까지 90일 일별 소비(오래된 날부터)
     * @return key → 예측 합계(원). 부를 수 없거나 실패·계약 위반이면 empty
     */
    Optional<Map<String, Long>> predict(LocalDate asOf, int horizonDays, Map<String, long[]> histories) {
        if (restClient == null || histories.isEmpty()) {
            return Optional.empty();
        }
        List<Map<String, Object>> accounts = histories.entrySet().stream()
                .map(entry -> Map.<String, Object>of("key", entry.getKey(), "dailySpending", entry.getValue()))
                .toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("asOf", asOf.toString());
        body.put("horizonDays", horizonDays);
        body.put("accounts", accounts);
        JsonNode response;
        try {
            response = restClient.post().uri("/forecast/living-cost").contentType(MediaType.APPLICATION_JSON)
                    .body(body).retrieve().body(JsonNode.class);
        } catch (RestClientException e) {
            // 시간 초과·연결 실패·200이 아닌 응답·읽을 수 없는 본문. 응답 본문은 남기지 않는다.
            log.warn("AI 생활비 예측 실패, TSB로 계산: horizon={} accounts={} cause={}", horizonDays, histories.size(),
                    e.getClass().getSimpleName());
            return Optional.empty();
        }
        Optional<Map<String, Long>> predictions = readPredictions(response, horizonDays, histories.keySet().size());
        if (predictions.isEmpty() || !predictions.get().keySet().equals(histories.keySet())) {
            log.warn("AI 생활비 예측 응답이 계약과 달라 TSB로 계산: horizon={} accounts={}", horizonDays, histories.size());
            return Optional.empty();
        }
        return predictions;
    }

    /** 예측 일수가 같고, 예측마다 key 문자열과 0 이상 정수 금액이 있고, key가 겹치지 않을 때만 읽는다. */
    private static Optional<Map<String, Long>> readPredictions(JsonNode response, int horizonDays, int expected) {
        if (response == null || !response.path("horizonDays").isIntegralNumber()
                || response.path("horizonDays").asInt() != horizonDays || !response.path("predictions").isArray()
                || response.path("predictions").size() != expected) {
            return Optional.empty();
        }
        Map<String, Long> out = new HashMap<>();
        for (JsonNode prediction : response.path("predictions")) {
            JsonNode key = prediction.path("key");
            JsonNode amount = prediction.path("amount");
            if (!key.isTextual() || !amount.isIntegralNumber() || !amount.canConvertToLong() || amount.asLong() < 0
                    || out.put(key.asText(), amount.asLong()) != null) {
                return Optional.empty();
            }
        }
        return Optional.of(out);
    }
}
