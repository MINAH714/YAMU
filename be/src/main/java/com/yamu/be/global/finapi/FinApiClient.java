package com.yamu.be.global.finapi;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * SSAFY 금융망 호출 창구. 모든 금융망 API가 POST이고 본문 맨 위에 공통 {@code Header}를 넣는 규칙을 여기서 처리한다.
 *
 * <pre>
 * 요청: { "Header": { apiName, transmissionDate, transmissionTime, institutionCode, fintechAppNo, apiServiceCode,
 *                     institutionTransactionUniqueNo, apiKey, userKey }, ...본문 }
 * 성공: HTTP 2xx + Header.responseCode = "H0000" → REC를 돌려준다
 * 거절: HTTP 2xx·4xx + Header.responseCode 또는 최상위 responseCode에 오류 코드 → {@link FinApiException}(REJECTED)
 * 결과 불명: HTTP 5xx(코드가 있어도), 코드 없음, 성공인데 필요한 값(REC, 응답 타입의 @NotNull·@NotBlank 필드)이 빠짐 → UNKNOWN
 * </pre>
 *
 * <p>실패는 세 가지로 나눈다({@link FinApiOutcome}). 자동으로 다시 보내지 않는다. 출금이 두 번 나갈 수 있기 때문이다.
 * 키·userKey·본문(계좌번호)은 로그에 남기지 않고, API 이름·기관거래고유번호·응답 코드·걸린 시간만 남긴다.
 */
@Slf4j
public class FinApiClient {

    static final String SUCCESS = "H0000";
    private static final String INSTITUTION_CODE = "00100";
    private static final String FINTECH_APP_NO = "001";
    private static final Pattern TRANSACTION_NO = Pattern.compile("\\d{20}");

    private final RestClient restClient;
    private final FinApiProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final FinTransactionNoIssuer transactionNoIssuer;
    private final Validator validator;

    public FinApiClient(RestClient restClient, FinApiProperties properties, ObjectMapper objectMapper, Clock clock,
            FinTransactionNoIssuer transactionNoIssuer, Validator validator) {
        this.restClient = restClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.transactionNoIssuer = transactionNoIssuer;
        this.validator = validator;
    }

    /**
     * 기관거래고유번호(20자리 숫자, {@link FinTransactionNoIssuer}). 같은 날 같은 API 안에서 겹치면 금융망이 H1007로 거절한다.
     * 출금·입금은 이 번호를 먼저 만들어 DB에 PROCESSING과 함께 저장한 뒤 {@link #post(String, String, String, Object, Class)}로 넘긴다.
     * 그래야 응답 시간 초과 때 그 번호로 거래내역을 찾아 결과를 확정할 수 있다. 채번에 실패하면 NOT_SENT.
     */
    public String newTransactionNo() {
        return transactionNoIssuer.issue();
    }

    /**
     * 사용자 API를 부른다. 기관거래고유번호는 새로 만든다. 조회처럼 다시 불러도 되는 API에 쓴다.
     *
     * @param path    {@code /edu/demandDeposit/inquireDemandDepositAccountBalance}처럼 base-url 뒤의 경로. 마지막 조각이 apiName이 된다
     * @param userKey 사용자 키. 관리자 API(상품 생성 등)는 null
     * @param body    Header 외의 요청 필드. record·Map 모두 된다. 필드가 없으면 null
     * @param recType 응답 REC를 담을 타입. REC가 목록이면 {@link JsonNode}로 받아 꺼낸다.
     *                REC를 쓰지 않는 API는 {@code Void.class}를 넘긴다. 그때만 REC가 없어도 성공이고, 그 밖의 타입은 REC가 없으면 결과 불명이다.
     *                값이 꼭 있어야 하는 필드는 타입에 {@code @NotNull}·{@code @NotBlank}를 붙인다. 빠지면 결과 불명이다
     */
    public <T> T post(String path, String userKey, Object body, Class<T> recType) {
        apiNameOf(path); // 경로가 틀리면 번호를 쓰기 전에 실패한다
        return post(path, userKey, newTransactionNo(), body, recType);
    }

    /** 출금·입금처럼 호출하는 쪽이 저장해 둔 기관거래고유번호({@link #newTransactionNo()})로 부를 때 쓴다. */
    public <T> T post(String path, String userKey, String transactionNo, Object body, Class<T> recType) {
        String apiName = apiNameOf(path);
        if (transactionNo == null || !TRANSACTION_NO.matcher(transactionNo).matches()) {
            throw new IllegalArgumentException("기관거래고유번호는 20자리 숫자여야 합니다(newTransactionNo)");
        }
        ObjectNode payload = toObject(body);
        payload.set("Header", header(apiName, userKey, transactionNo));
        Reply reply = send(path, apiName, transactionNo, payload);
        String code = responseCode(reply.root());
        if (!reply.isSuccessful() || !SUCCESS.equals(code)) {
            throw failure(apiName, transactionNo, reply.status(), code);
        }
        if (recType == Void.class) {
            return null;
        }
        JsonNode rec = reply.root().get("REC");
        if (rec == null || rec.isNull()) {
            log.warn("금융망 {} tx={} 성공인데 REC 없음 → 결과 불명", apiName, transactionNo);
            throw FinApiException.brokenResponse(apiName);
        }
        return convert(rec, recType, apiName);
    }

    /**
     * Header 없이 본문에 apiKey를 넣는 앱 API(회원 생성 {@code /member}, 회원 조회 {@code /member/search})를 부른다.
     * 성공 응답은 responseCode·REC 없이 최상위에 필드가 오므로 응답 전체를 {@code responseType}으로 바꾼다.
     * 코드가 없으니 HTTP 상태와 필수 필드로 성공을 가린다. 예: 회원 응답 타입의 userKey에 {@code @NotBlank}를 붙이면
     * 2xx라도 userKey가 없을 때 결과 불명으로 끝나, userKey 없는 회원이 저장되지 않는다.
     */
    public <T> T postWithApiKey(String path, Object body, Class<T> responseType) {
        String apiName = apiNameOf(path);
        ObjectNode payload = toObject(body);
        payload.put("apiKey", requireApiKey());
        Reply reply = send(path, apiName, null, payload);
        String code = responseCode(reply.root());
        if (!reply.isSuccessful() || (code != null && !SUCCESS.equals(code))) {
            throw failure(apiName, null, reply.status(), code);
        }
        return convert(reply.root(), responseType, apiName);
    }

    private Reply send(String path, String apiName, String transactionNo, ObjectNode payload) {
        long started = clock.millis();
        Response response;
        try {
            // exchange 안에서 본문을 읽다 난 IOException도 ResourceAccessException으로 감싸져 아래에서 분류된다.
            response = restClient.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .exchange((request, res) -> new Response(res.getStatusCode().value(),
                            new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8)));
        } catch (ResourceAccessException e) {
            throw classifyIoFailure(apiName, transactionNo, e, clock.millis() - started);
        }
        long elapsed = clock.millis() - started;
        JsonNode root = readObject(response.body());
        if (root == null) {
            log.warn("금융망 {} tx={} http={} 응답을 읽지 못함 {}ms → 결과 불명", apiName, transactionNo, response.status(), elapsed);
            throw FinApiException.brokenResponse(apiName);
        }
        log.info("금융망 {} tx={} http={} code={} {}ms", apiName, transactionNo, response.status(), responseCode(root), elapsed);
        return new Reply(response.status(), root);
    }

    /** JSON 객체가 아니면 null. */
    private JsonNode readObject(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            return root != null && root.isObject() ? root : null;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /**
     * 연결 단계에서 실패하면 요청이 가지 않은 것(NOT_SENT), 보낸 뒤 응답을 기다리다 실패하면 결과 불명(UNKNOWN)이다.
     * JDK HttpClient는 연결 시간 초과를 {@link HttpConnectTimeoutException}, 응답 시간 초과를 {@link HttpTimeoutException}으로 던진다.
     */
    private FinApiException classifyIoFailure(String apiName, String transactionNo, ResourceAccessException e, long elapsed) {
        Throwable cause = e.getCause();
        if (cause instanceof HttpConnectTimeoutException || cause instanceof ConnectException
                || cause instanceof NoRouteToHostException || cause instanceof UnknownHostException) {
            log.warn("금융망 {} tx={} 연결 실패({}) {}ms → 요청 안 감", apiName, transactionNo, simpleName(cause), elapsed);
            return FinApiException.notSent(apiName);
        }
        if (cause instanceof HttpTimeoutException) {
            log.warn("금융망 {} tx={} 응답 시간 초과 {}ms → 결과 불명", apiName, transactionNo, elapsed);
            return FinApiException.timedOut(apiName);
        }
        log.warn("금융망 {} tx={} 통신 오류({}) {}ms → 결과 불명", apiName, transactionNo, simpleName(cause), elapsed);
        return FinApiException.brokenResponse(apiName);
    }

    /**
     * 5xx는 오류 코드가 있어도 결과 불명이다. 금융망이 출금을 처리한 뒤 서버 오류가 났을 수 있어, 거절로 보면 실제로 나간 돈을 놓친다.
     * 그 밖에는 오류 코드가 있으면 거절, 없으면(또는 2xx가 아닌데 성공 코드면) 결과 불명이다.
     */
    private static FinApiException failure(String apiName, String transactionNo, int status, String code) {
        if (status >= 500) {
            log.warn("금융망 {} tx={} http={} code={} 서버 오류 → 결과 불명", apiName, transactionNo, status, code);
            return FinApiException.brokenResponse(apiName, code);
        }
        if (code == null || SUCCESS.equals(code)) {
            log.warn("금융망 {} tx={} http={} 응답 코드 없음 → 결과 불명", apiName, transactionNo, status);
            return FinApiException.brokenResponse(apiName);
        }
        log.warn("금융망 {} tx={} http={} 거절 code={}", apiName, transactionNo, status, code);
        return FinApiException.rejected(apiName, code);
    }

    /** transmissionDate·transmissionTime은 기관거래고유번호의 앞 14자리(한국 시각)와 맞춘다(스키마 사전 채번 규칙). */
    private ObjectNode header(String apiName, String userKey, String transactionNo) {
        ObjectNode header = objectMapper.createObjectNode();
        header.put("apiName", apiName);
        header.put("transmissionDate", transactionNo.substring(0, 8));
        header.put("transmissionTime", transactionNo.substring(8, 14));
        header.put("institutionCode", INSTITUTION_CODE);
        header.put("fintechAppNo", FINTECH_APP_NO);
        header.put("apiServiceCode", apiName);
        header.put("institutionTransactionUniqueNo", transactionNo);
        header.put("apiKey", requireApiKey());
        if (userKey != null) {
            header.put("userKey", userKey);
        }
        return header;
    }

    /** 성공은 Header.responseCode, 오류는 Header 또는 최상위 responseCode에 온다. 없으면 null. */
    private static String responseCode(JsonNode root) {
        JsonNode code = root.path("Header").path("responseCode");
        if (code.isMissingNode() || code.isNull()) {
            code = root.path("responseCode");
        }
        return code.isTextual() ? code.asText() : null;
    }

    private <T> T convert(JsonNode node, Class<T> type, String apiName) {
        if (type == Void.class) {
            return null;
        }
        if (type == JsonNode.class) {
            return type.cast(node);
        }
        T value;
        try {
            value = objectMapper.treeToValue(node, type);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            log.warn("금융망 {} 성공 응답을 {}로 바꾸지 못함 → 결과 불명", apiName, type.getSimpleName());
            throw FinApiException.brokenResponse(apiName);
        }
        Set<ConstraintViolation<T>> violations = value == null ? Set.of() : validator.validate(value);
        if (value == null || !violations.isEmpty()) {
            // 값은 남기지 않고 빠진 필드 이름만 남긴다(userKey 같은 값이 로그에 남지 않게).
            String fields = violations.stream().map(v -> v.getPropertyPath().toString()).sorted()
                    .collect(Collectors.joining(","));
            log.warn("금융망 {} 성공 응답에 필요한 값 없음({}) → 결과 불명", apiName, fields);
            throw FinApiException.brokenResponse(apiName);
        }
        return value;
    }

    private ObjectNode toObject(Object body) {
        if (body == null) {
            return objectMapper.createObjectNode();
        }
        JsonNode node = objectMapper.valueToTree(body);
        if (!(node instanceof ObjectNode object)) {
            throw new IllegalArgumentException("금융망 요청 본문은 객체여야 합니다: " + body.getClass().getSimpleName());
        }
        if (object.has("Header") || object.has("apiKey")) {
            throw new IllegalArgumentException("Header·apiKey는 FinApiClient가 넣습니다");
        }
        return object;
    }

    private String requireApiKey() {
        if (!properties.hasApiKey()) {
            throw new IllegalStateException("yamu.fin-api.api-key가 비어 있습니다. 운영은 SSAFY_API_KEY 환경변수, 로컬은 application-local.yml을 확인하세요.");
        }
        return properties.apiKey();
    }

    private static String apiNameOf(String path) {
        if (path == null || !path.startsWith("/") || path.endsWith("/")) {
            throw new IllegalArgumentException("금융망 경로는 /로 시작하고 API 이름으로 끝나야 합니다: " + path);
        }
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static String simpleName(Throwable t) {
        return t == null ? "unknown" : t.getClass().getSimpleName();
    }

    private record Response(int status, String body) {
    }

    private record Reply(int status, JsonNode root) {

        boolean isSuccessful() {
            return status >= 200 && status < 300;
        }
    }
}
