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
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * AI 서버의 생활비 예측({@code POST /forecast/living-cost})과 신용 생활 소비 예측({@code POST /forecast/credit-spending},
 * S15P31E103-404)을 부른다(ai/README.md). 두 API는 요청·응답 모양이 같다. 내부 전용 API라 BE 공통 응답 형식이 아니다.
 *
 * <p>AI 서버는 한 요청에 key {@value #MAX_KEYS}개까지 받는다. 더 많으면 {@value #MAX_KEYS}개씩 나눠 차례로 부르고, 하나라도
 * 실패하면 전체를 empty로 돌려준다(S15P31E103-405, -404 Codex 검토).
 *
 * <p>응답을 믿지 않는다. 200이 아니거나, 시간 제한을 넘거나, 계약(예측 일수 일치, 비어 있지 않은 모델 버전 문자열, 보낸 key마다
 * 0 이상 {@value #MAX_DAILY_AMOUNT}원 × 예측 일수 이하 정수 하나)을 어기면 empty를 돌려주고 부르는 쪽이 그 요청 전체를 TSB로
 * 계산한다(2026-11-05 결정: 계약 검사만). 상한은 AI 서버가 받는 하루 입력 상한과 같아, 뒤의 계산이 넘치지 않는다(코드 리뷰 반영).
 * 모델 버전은 예측 이력에 남기려고 읽는다(-405 Codex 검토). 나눠 부른 요청끼리 버전이 다르면 계약 위반이다.
 *
 * <p>시간 초과·연결 실패가 나면 서버 장애로 보고 failurePause 동안 두 API 모두 부르지 않는다. 5xx는 그 API의 모델 문제일 수
 * 있어(예: 신용 모델만 못 읽어 503) <b>그 API만</b> 멈춘다(-405 Codex 검토, 2026-11-11 결정: 신용 모델 장애가 체크 생활비 AI를
 * 멈추지 않게). 장애가 이어져도 예보 요청마다 시간 제한만큼 기다리지 않게 하려는 것이다(코드 리뷰 반영, 2026-11-05).
 * 4xx·읽을 수 없는 본문·계약 위반은 그 요청만의 문제이고 빠르게 끝나므로 멈추지 않는다(2026-11-06 !136 리뷰).
 * 요청·응답 값은 로그에 남기지 않는다(계좌 수·예측 일수·실패 종류만).
 */
@Slf4j
class AiLivingCostClient {
    /** AI 서버가 받는 하루 소비 상한(ai/app/main.py MAX_DAILY_AMOUNT). 예측이 이 값 × 예측 일수를 넘으면 계약 위반이다. */
    static final long MAX_DAILY_AMOUNT = 1_000_000_000_000L;
    /** AI 서버가 한 요청에 받는 key 수 상한(ai/app/main.py MAX_ACCOUNTS). */
    static final int MAX_KEYS = 20;
    /** 예측 이력의 model_version 칸 길이. 이보다 긴 버전은 계약 위반이다. */
    static final int MAX_MODEL_VERSION_LENGTH = 255;

    /** 부를 예측 API. */
    enum Endpoint {
        /** 계좌별 체크카드 생활 소비. */
        LIVING_COST("/forecast/living-cost"),
        /** 신용카드별 신용 생활 소비(이번 이용기간 마감 전 사용 추정, S15P31E103-405). */
        CREDIT_SPENDING("/forecast/credit-spending");

        private final String path;

        Endpoint(String path) {
            this.path = path;
        }
    }

    private final RestClient restClient;
    private final Clock clock;
    private final Duration failurePause;
    /** 시간 초과·연결 실패 뒤 이 시각 전에는 어느 API도 부르지 않는다. 여러 요청이 동시에 읽고 쓴다. */
    private volatile Instant serverPausedUntil = Instant.MIN;
    /** 5xx 뒤 API마다 이 시각 전에는 그 API를 부르지 않는다. */
    private final Map<Endpoint, Instant> endpointPausedUntil = new ConcurrentHashMap<>();

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
     * key마다 다음 horizonDays일(asOf 다음 날부터) 생활 소비 합계를 예측한다. key가 {@value #MAX_KEYS}개를 넘으면 나눠 부른다.
     *
     * @param endpoint    체크 생활비 또는 신용 생활 소비
     * @param asOf        기록의 마지막 날(한국 날짜)
     * @param horizonDays 7·14·30 중 하나
     * @param histories   key(계좌번호·카드 번호·사용자 ID가 아닌 임의 값) → asOf까지 90일 일별 소비(오래된 날부터)
     * @return 모델 버전과 key → 예측 합계(원). 부를 수 없거나 하나라도 실패·계약 위반이면 empty
     */
    Optional<Answer> predict(Endpoint endpoint, LocalDate asOf, int horizonDays, Map<String, long[]> histories) {
        if (restClient == null || histories.isEmpty()) {
            return Optional.empty();
        }
        List<Map.Entry<String, long[]>> entries = List.copyOf(histories.entrySet());
        Map<String, Long> out = new HashMap<>();
        String modelVersion = null;
        for (int from = 0; from < entries.size(); from += MAX_KEYS) {
            Map<String, long[]> chunk = new LinkedHashMap<>();
            entries.subList(from, Math.min(from + MAX_KEYS, entries.size()))
                    .forEach(entry -> chunk.put(entry.getKey(), entry.getValue()));
            Optional<Answer> answer = predictChunk(endpoint, asOf, horizonDays, chunk);
            if (answer.isEmpty()) {
                return Optional.empty();
            }
            if (modelVersion != null && !modelVersion.equals(answer.get().modelVersion())) {
                // 나눠 부르는 사이 모델이 바뀌면 한 요청 안에 두 버전이 섞인다. 섞인 값은 쓰지 않는다
                log.warn("AI 예측 응답의 모델 버전이 나눠 부른 요청끼리 달라 TSB로 계산: endpoint={} horizon={} keys={}",
                        endpoint, horizonDays, histories.size());
                return Optional.empty();
            }
            modelVersion = answer.get().modelVersion();
            out.putAll(answer.get().amounts());
        }
        return Optional.of(new Answer(modelVersion, out));
    }

    private Optional<Answer> predictChunk(Endpoint endpoint, LocalDate asOf, int horizonDays,
            Map<String, long[]> histories) {
        Instant now = clock.instant();
        if (now.isBefore(serverPausedUntil) || now.isBefore(endpointPausedUntil.getOrDefault(endpoint, Instant.MIN))) {
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
            response = restClient.post().uri(endpoint.path).contentType(MediaType.APPLICATION_JSON)
                    .body(body).retrieve().body(JsonNode.class);
        } catch (ResourceAccessException e) {
            // 시간 초과·연결 실패: AI 서버 장애로 보고 두 API 모두 잠시 부르지 않는다. 응답 본문은 남기지 않는다.
            serverPausedUntil = clock.instant().plus(failurePause);
            log.warn("AI 서버 연결 실패, TSB로 계산하고 {} 동안 두 API 모두 부르지 않음: endpoint={} horizon={} keys={} cause={}",
                    failurePause, endpoint, horizonDays, histories.size(), e.getClass().getSimpleName());
            return Optional.empty();
        } catch (HttpServerErrorException e) {
            // 5xx: 그 API의 모델 장애일 수 있어 그 API만 잠시 부르지 않는다(-405 Codex 검토).
            endpointPausedUntil.put(endpoint, clock.instant().plus(failurePause));
            log.warn("AI 예측 서버 오류, TSB로 계산하고 {} 동안 이 API만 부르지 않음: endpoint={} horizon={} keys={} status={}",
                    failurePause, endpoint, horizonDays, histories.size(), e.getStatusCode().value());
            return Optional.empty();
        } catch (RestClientException e) {
            // 4xx·읽을 수 없는 본문: 이 요청의 문제라 이 요청만 TSB로 계산하고 멈추지 않는다(!136 리뷰).
            log.warn("AI 예측 응답을 쓸 수 없어 이 요청만 TSB로 계산: endpoint={} horizon={} keys={} cause={}",
                    endpoint, horizonDays, histories.size(), e.getClass().getSimpleName());
            return Optional.empty();
        }
        Optional<Answer> predictions = readPredictions(response, horizonDays, histories.keySet().size());
        if (predictions.isEmpty() || !predictions.get().amounts().keySet().equals(histories.keySet())) {
            log.warn("AI 예측 응답이 계약과 달라 TSB로 계산: endpoint={} horizon={} keys={}", endpoint, horizonDays,
                    histories.size());
            return Optional.empty();
        }
        return predictions;
    }

    /**
     * 예측 일수가 같고, 모델 버전이 비어 있지 않은 {@value #MAX_MODEL_VERSION_LENGTH}자 이하 문자열이고, 예측마다 key 문자열과
     * 0 이상·상한 이하 정수 금액이 있고, key가 겹치지 않을 때만 읽는다.
     */
    private static Optional<Answer> readPredictions(JsonNode response, int horizonDays, int expected) {
        if (response == null || !response.path("horizonDays").isIntegralNumber()
                || response.path("horizonDays").asInt() != horizonDays || !response.path("predictions").isArray()
                || response.path("predictions").size() != expected) {
            return Optional.empty();
        }
        JsonNode version = response.path("modelVersion");
        if (!version.isTextual() || version.asText().isBlank()
                || version.asText().length() > MAX_MODEL_VERSION_LENGTH) {
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
        return Optional.of(new Answer(version.asText(), out));
    }

    /**
     * @param modelVersion AI 서버가 답한 모델 버전(예: {@code two-stage-tree-credit-20261111-…-bemedian})
     * @param amounts      key → 예측 합계(원)
     */
    record Answer(String modelVersion, Map<String, Long> amounts) {}
}
