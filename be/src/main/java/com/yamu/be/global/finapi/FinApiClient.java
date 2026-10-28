package com.yamu.be.global.finapi;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
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
 * 성공: HTTP 200 + Header.responseCode = "H0000" → REC를 돌려준다
 * 실패: Header.responseCode 또는 최상위 responseCode에 오류 코드 → {@link FinApiException}(REJECTED)
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
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HHmmss");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final RestClient restClient;
    private final FinApiProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public FinApiClient(RestClient restClient, FinApiProperties properties, ObjectMapper objectMapper, Clock clock) {
        this.restClient = restClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 기관거래고유번호(20자리 숫자). 같은 날 같은 API 안에서 겹치면 금융망이 H1007로 거절한다.
     * 출금·입금은 이 번호를 먼저 만들어 DB에 PROCESSING과 함께 저장한 뒤 {@link #post(String, String, String, Object, Class)}로 넘긴다.
     * 그래야 응답 시간 초과 때 그 번호로 거래내역을 찾아 결과를 확정할 수 있다.
     */
    public static String newTransactionNo() {
        StringBuilder no = new StringBuilder(20);
        for (int i = 0; i < 20; i++) {
            no.append(RANDOM.nextInt(10));
        }
        return no.toString();
    }

    /**
     * 사용자 API를 부른다. 기관거래고유번호는 새로 만든다. 조회처럼 다시 불러도 되는 API에 쓴다.
     *
     * @param path    {@code /edu/demandDeposit/inquireDemandDepositAccountBalance}처럼 base-url 뒤의 경로. 마지막 조각이 apiName이 된다
     * @param userKey 사용자 키. 관리자 API(상품 생성 등)는 null
     * @param body    Header 외의 요청 필드. record·Map 모두 된다. 필드가 없으면 null
     * @param recType 응답 REC를 담을 타입. REC가 목록이면 {@link JsonNode}로 받아 꺼낸다
     */
    public <T> T post(String path, String userKey, Object body, Class<T> recType) {
        return post(path, userKey, newTransactionNo(), body, recType);
    }

    /** 출금·입금처럼 호출하는 쪽이 저장해 둔 기관거래고유번호로 부를 때 쓴다. */
    public <T> T post(String path, String userKey, String transactionNo, Object body, Class<T> recType) {
        String apiName = apiNameOf(path);
        ObjectNode payload = toObject(body);
        payload.set("Header", header(apiName, userKey, transactionNo));
        JsonNode root = send(path, apiName, transactionNo, payload);
        String code = responseCode(root);
        if (!SUCCESS.equals(code)) {
            throw rejectedOrBroken(apiName, transactionNo, code);
        }
        return convert(root.get("REC"), recType, apiName);
    }

    /**
     * Header 없이 본문에 apiKey를 넣는 앱 API(회원 생성 {@code /member}, 회원 조회 {@code /member/search})를 부른다.
     * 성공 응답은 REC 없이 최상위에 필드가 오므로 응답 전체를 {@code responseType}으로 바꾼다.
     */
    public <T> T postWithApiKey(String path, Object body, Class<T> responseType) {
        String apiName = apiNameOf(path);
        ObjectNode payload = toObject(body);
        payload.put("apiKey", requireApiKey());
        JsonNode root = send(path, apiName, null, payload);
        String code = responseCode(root);
        if (code != null && !SUCCESS.equals(code)) {
            throw rejectedOrBroken(apiName, null, code);
        }
        return convert(root, responseType, apiName);
    }

    private JsonNode send(String path, String apiName, String transactionNo, ObjectNode payload) {
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
        return root;
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
                || cause instanceof UnknownHostException) {
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

    private static FinApiException rejectedOrBroken(String apiName, String transactionNo, String code) {
        if (code == null) {
            log.warn("금융망 {} tx={} 응답 코드 없음 → 결과 불명", apiName, transactionNo);
            return FinApiException.brokenResponse(apiName);
        }
        log.warn("금융망 {} tx={} 거절 code={}", apiName, transactionNo, code);
        return FinApiException.rejected(apiName, code);
    }

    private ObjectNode header(String apiName, String userKey, String transactionNo) {
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(KST));
        ObjectNode header = objectMapper.createObjectNode();
        header.put("apiName", apiName);
        header.put("transmissionDate", now.format(DATE));
        header.put("transmissionTime", now.format(TIME));
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
        try {
            return objectMapper.treeToValue(node, type);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            log.warn("금융망 {} 성공 응답을 {}로 바꾸지 못함 → 결과 불명", apiName, type.getSimpleName());
            throw FinApiException.brokenResponse(apiName);
        }
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
}
