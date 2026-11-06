package com.yamu.be.forecast;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * AI 서버의 생활비 예측({@code POST /forecast/living-cost}, ai/README.md)을 부른다. 내부 전용 API라 BE 공통 응답 형식이 아니다.
 *
 * <p>응답을 믿지 않는다. 200이 아니거나, 시간 제한을 넘거나, 계약(예측 일수 일치, 보낸 key마다 0 이상
 * {@value #MAX_DAILY_AMOUNT}원 × 예측 일수 이하 정수 하나)을 어기면 empty를 돌려주고 부르는 쪽이 그 요청 전체를 TSB로
 * 계산한다(2026-11-05 결정: 계약 검사만). 상한은 AI 서버가 받는 하루 입력 상한과 같아, 뒤의 계산이 넘치지 않는다(코드 리뷰 반영).
 *
 * <p>시간 초과·연결 실패·5xx가 나면 failurePause 동안 부르지 않고 바로 empty를 돌려준다. 장애가 이어져도 예보
 * 요청마다 시간 제한만큼 기다리지 않게 하려는 것이다(코드 리뷰 반영, 2026-11-05). 4xx·읽을 수 없는 본문·계약 위반은
 * 그 요청만의 문제이고 빠르게 끝나므로 멈추지 않는다(2026-11-06 !136 리뷰).
 * 요청·응답 값은 로그에 남기지 않는다(계좌 수·예측 일수·실패 종류만).
 */
@Slf4j
class AiLivingCostClient {
    /** AI 서버가 받는 하루 소비 상한(ai/app/main.py MAX_DAILY_AMOUNT). 예측이 이 값 × 예측 일수를 넘으면 계약 위반이다. */
    static final long MAX_DAILY_AMOUNT = 1_000_000_000_000L;

    private final RestClient restClient;
    private final Clock clock;
    private final Duration failurePause;
    /** 이 시각 전에는 부르지 않는다. 여러 요청이 동시에 읽고 쓴다. */
    private volatile Instant pausedUntil = Instant.MIN;

    /**
     * @param restClient   AI 서버 주소가 정해진 클라이언트. null이면 AI를 부르지 않는다(주소 미설정)
     * @param clock        멈춤 시각을 셀 시계
     * @param failurePause 실패 뒤 부르지 않는 시간
     */
    AiLivingCostClient(RestClient restClient, Clock clock, Duration failurePause) {
        this.restClient = restClient;
        this.clock = clock;
        this.failurePause = failurePause;
    }

    /** AI 서버 주소가 없을 때. 늘 empty를 돌려준다. */
    static AiLivingCostClient disabled() {
        return new AiLivingCostClient(null, Clock.systemUTC(), Duration.ZERO);
    }

    boolean enabled() {
        return restClient != null;
    }

    /**
     * 계좌마다 다음 horizonDays일(asOf 다음 날부터) 체크카드 생활 소비 합계를 예측한다.
     *
     * @param asOf        기록의 마지막 날(한국 날짜)
     * @param horizonDays 7·14·30 중 하나
     * @param histories   key(계좌번호·사용자 ID가 아닌 임의 값) → asOf까지 90일 일별 소비(오래된 날부터)
     * @return key → 예측 합계(원). 부를 수 없거나 실패·계약 위반이면 empty
     */
    Optional<Map<String, Long>> predict(LocalDate asOf, int horizonDays, Map<String, long[]> histories) {
        if (restClient == null || histories.isEmpty() || clock.instant().isBefore(pausedUntil)) {
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
        } catch (ResourceAccessException | HttpServerErrorException e) {
            // 시간 초과·연결 실패·5xx: AI 서버 장애로 보고 잠시 부르지 않는다. 응답 본문은 남기지 않는다.
            pausedUntil = clock.instant().plus(failurePause);
            log.warn("AI 생활비 예측 실패, TSB로 계산하고 {} 동안 부르지 않음: horizon={} accounts={} cause={}",
                    failurePause, horizonDays, histories.size(), e.getClass().getSimpleName());
            return Optional.empty();
        } catch (RestClientException e) {
            // 4xx·읽을 수 없는 본문: 이 요청의 문제라 이 요청만 TSB로 계산하고 멈추지 않는다(!136 리뷰).
            log.warn("AI 생활비 예측 응답을 쓸 수 없어 이 요청만 TSB로 계산: horizon={} accounts={} cause={}",
                    horizonDays, histories.size(), e.getClass().getSimpleName());
            return Optional.empty();
        }
        Optional<Map<String, Long>> predictions = readPredictions(response, horizonDays, histories.keySet().size());
        if (predictions.isEmpty() || !predictions.get().keySet().equals(histories.keySet())) {
            log.warn("AI 생활비 예측 응답이 계약과 달라 TSB로 계산: horizon={} accounts={}", horizonDays, histories.size());
            return Optional.empty();
        }
        return predictions;
    }

    /** 예측 일수가 같고, 예측마다 key 문자열과 0 이상·상한 이하 정수 금액이 있고, key가 겹치지 않을 때만 읽는다. */
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
                    || amount.asLong() > MAX_DAILY_AMOUNT * horizonDays || out.put(key.asText(), amount.asLong()) != null) {
                return Optional.empty();
            }
        }
        return Optional.of(out);
    }
}
