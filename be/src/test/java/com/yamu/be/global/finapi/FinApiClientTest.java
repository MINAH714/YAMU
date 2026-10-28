package com.yamu.be.global.finapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yamu.be.global.exception.ErrorCode;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * 금융망을 실제로 부르지 않고 MockRestServiceServer로 요청 모양과 응답 처리를 확인한다.
 * 응답 예시는 SSAFY 문서와 10/27 실측 형태를 따른다.
 */
class FinApiClientTest {

    private static final String BASE = "https://fin.test/ssafy/api/v1";
    private static final String API_KEY = "test-api-key";
    private static final String USER_KEY = "test-user-key";
    private static final String BALANCE = "/edu/demandDeposit/inquireDemandDepositAccountBalance";
    /** 2026-10-28 00:30:15 UTC = 한국 09:30:15 */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-28T00:30:15Z"), ZoneOffset.UTC);

    MockRestServiceServer server;
    FinApiClient client;

    record BalanceRequest(String accountNo) {
    }

    record BalanceRec(String bankCode, String accountNo, Long accountBalance) {
    }

    record Member(String userId, String userKey) {
    }

    @BeforeEach
    void setUp() {
        client = clientWith(API_KEY);
    }

    private FinApiClient clientWith(String apiKey) {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();
        FinApiProperties properties = new FinApiProperties(BASE, apiKey, Duration.ofSeconds(3), Duration.ofSeconds(10));
        return new FinApiClient(builder.build(), properties, objectMapper, CLOCK);
    }

    // ───────── 요청 모양 ─────────

    @Test
    void 공통_Header를_붙이고_본문과_합쳐_보낸다() {
        server.expect(requestTo(BASE + BALANCE))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.Header.apiName").value("inquireDemandDepositAccountBalance"))
                .andExpect(jsonPath("$.Header.apiServiceCode").value("inquireDemandDepositAccountBalance"))
                .andExpect(jsonPath("$.Header.transmissionDate").value("20261028"))
                .andExpect(jsonPath("$.Header.transmissionTime").value("093015"))
                .andExpect(jsonPath("$.Header.institutionCode").value("00100"))
                .andExpect(jsonPath("$.Header.fintechAppNo").value("001"))
                .andExpect(jsonPath("$.Header.institutionTransactionUniqueNo").value(Matchers.matchesPattern("\\d{20}")))
                .andExpect(jsonPath("$.Header.apiKey").value(API_KEY))
                .andExpect(jsonPath("$.Header.userKey").value(USER_KEY))
                .andExpect(jsonPath("$.accountNo").value("0885170441365934"))
                .andRespond(withSuccess(success("""
                        {"bankCode":"088","accountNo":"0885170441365934","accountBalance":1000000}
                        """), MediaType.APPLICATION_JSON));

        BalanceRec rec = client.post(BALANCE, USER_KEY, new BalanceRequest("0885170441365934"), BalanceRec.class);

        assertThat(rec).isEqualTo(new BalanceRec("088", "0885170441365934", 1_000_000L));
        server.verify();
    }

    @Test
    void 호출하는_쪽이_준_기관거래고유번호를_그대로_쓴다() {
        String transactionNo = FinApiClient.newTransactionNo();
        server.expect(jsonPath("$.Header.institutionTransactionUniqueNo").value(transactionNo))
                .andRespond(withSuccess(success("{}"), MediaType.APPLICATION_JSON));

        client.post("/edu/demandDeposit/updateDemandDepositAccountWithdrawal", USER_KEY, transactionNo,
                Map.of("accountNo", "1", "transactionBalance", "1000"), JsonNode.class);

        server.verify();
    }

    @Test
    void 관리자_API는_userKey를_넣지_않는다() {
        server.expect(jsonPath("$.Header.userKey").doesNotExist())
                .andExpect(jsonPath("$.Header.apiKey").value(API_KEY))
                .andRespond(withSuccess(success("{}"), MediaType.APPLICATION_JSON));

        client.post("/edu/demandDeposit/createDemandDeposit", null, Map.of("bankCode", "088"), JsonNode.class);

        server.verify();
    }

    @Test
    void 기관거래고유번호는_20자리_숫자이고_매번_다르다() {
        String a = FinApiClient.newTransactionNo();
        String b = FinApiClient.newTransactionNo();

        assertThat(a).matches("\\d{20}");
        assertThat(a).isNotEqualTo(b);
    }

    // ───────── 금융망이 거절 ─────────

    @Test
    void Header에_오류_코드가_오면_REJECTED와_finCode() {
        server.expect(requestTo(BASE + BALANCE)).andRespond(withSuccess("""
                {"Header":{"responseCode":"A1014","responseMessage":"계좌잔액이 부족하여 거래가 실패했습니다."}}
                """, MediaType.APPLICATION_JSON));

        assertRejected(() -> client.post(BALANCE, USER_KEY, null, JsonNode.class), "A1014");
    }

    @Test
    void 최상위에_오류_코드가_오면_REJECTED와_finCode() {
        server.expect(requestTo(BASE + BALANCE)).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"responseCode":"A1003","responseMessage":"계좌번호가 유효하지 않습니다."}
                        """));

        assertRejected(() -> client.post(BALANCE, USER_KEY, null, JsonNode.class), "A1003");
    }

    @Test
    void 기관거래고유번호_중복은_H1007로_넘겨_호출하는_쪽이_거래내역으로_확인하게_한다() {
        server.expect(requestTo(BASE + BALANCE)).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"responseCode\":\"H1007\",\"responseMessage\":\"기관거래고유번호가 중복된 값입니다.\"}"));

        assertRejected(() -> client.post(BALANCE, USER_KEY, null, JsonNode.class), "H1007");
    }

    // ───────── 통신 실패 ─────────

    @Test
    void 연결이_거부되면_요청이_안_간_것() {
        server.expect(requestTo(BASE + BALANCE)).andRespond(withException(new ConnectException("Connection refused")));

        assertFailure(() -> client.post(BALANCE, USER_KEY, null, JsonNode.class),
                FinApiOutcome.NOT_SENT, ErrorCode.FIN_API_UNAVAILABLE);
    }

    @Test
    void 연결_시간_초과도_요청이_안_간_것() {
        server.expect(requestTo(BASE + BALANCE)).andRespond(withException(new HttpConnectTimeoutException("connect timed out")));

        assertFailure(() -> client.post(BALANCE, USER_KEY, null, JsonNode.class),
                FinApiOutcome.NOT_SENT, ErrorCode.FIN_API_UNAVAILABLE);
    }

    @Test
    void 응답_시간_초과는_결과_불명() {
        server.expect(requestTo(BASE + BALANCE)).andRespond(withException(new HttpTimeoutException("request timed out")));

        assertFailure(() -> client.post(BALANCE, USER_KEY, null, JsonNode.class),
                FinApiOutcome.UNKNOWN, ErrorCode.UPSTREAM_TIMEOUT);
    }

    @Test
    void 응답이_깨졌거나_코드가_없으면_결과_불명() {
        server.expect(requestTo(BASE + BALANCE)).andRespond(withStatus(HttpStatus.BAD_GATEWAY).body("<html>bad gateway</html>"));
        assertFailure(() -> client.post(BALANCE, USER_KEY, null, JsonNode.class),
                FinApiOutcome.UNKNOWN, ErrorCode.FIN_API_UNAVAILABLE);

        server.reset();
        server.expect(requestTo(BASE + BALANCE)).andRespond(withSuccess("{\"REC\":{}}", MediaType.APPLICATION_JSON));
        assertFailure(() -> client.post(BALANCE, USER_KEY, null, JsonNode.class),
                FinApiOutcome.UNKNOWN, ErrorCode.FIN_API_UNAVAILABLE);
    }

    // ───────── 앱 API (회원) ─────────

    @Test
    void 회원_API는_Header_없이_본문에_apiKey를_넣고_응답_전체를_돌려준다() {
        server.expect(requestTo(BASE + "/member"))
                .andExpect(jsonPath("$.Header").doesNotExist())
                .andExpect(jsonPath("$.apiKey").value(API_KEY))
                .andExpect(jsonPath("$.userId").value("yabc123456@yamu.app"))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body("""
                        {"userId":"yabc123456@yamu.app","userName":"yabc123456","userKey":"new-user-key","created":"2026-10-28T09:30:15"}
                        """));

        Member member = client.postWithApiKey("/member", Map.of("userId", "yabc123456@yamu.app"), Member.class);

        assertThat(member).isEqualTo(new Member("yabc123456@yamu.app", "new-user-key"));
    }

    @Test
    void 이미_있는_회원_ID면_E4002로_거절() {
        server.expect(requestTo(BASE + "/member")).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"responseCode\":\"E4002\",\"responseMessage\":\"이미 존재하는 ID입니다.\"}"));

        assertRejected(() -> client.postWithApiKey("/member", Map.of("userId", "dup@yamu.app"), Member.class), "E4002");
    }

    // ───────── 설정·입력 실수 ─────────

    @Test
    void api_key가_비어_있으면_보내기_전에_실패하고_요청이_나가지_않는다() {
        FinApiClient noKey = clientWith(" ");

        assertThatThrownBy(() -> noKey.post(BALANCE, USER_KEY, null, JsonNode.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("yamu.fin-api.api-key");
        server.verify();
    }

    @Test
    void Header나_apiKey를_본문에_직접_넣으면_거절한다() {
        assertThatThrownBy(() -> client.post(BALANCE, USER_KEY, Map.of("Header", Map.of()), JsonNode.class))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.postWithApiKey("/member", Map.of("apiKey", "x"), JsonNode.class))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 실패_메시지에_키가_들어가지_않는다() {
        server.expect(requestTo(BASE + BALANCE)).andRespond(withException(new ConnectException("refused")));

        assertThatThrownBy(() -> client.post(BALANCE, USER_KEY, null, JsonNode.class))
                .isInstanceOf(FinApiException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(API_KEY).doesNotContain(USER_KEY));
    }

    // ───────── helpers ─────────

    private static String success(String rec) {
        return """
                {"Header":{"responseCode":"H0000","responseMessage":"정상처리 되었습니다."},"REC":%s}
                """.formatted(rec);
    }

    private static void assertRejected(Runnable call, String finCode) {
        assertThatThrownBy(call::run)
                .isInstanceOf(FinApiException.class)
                .satisfies(e -> {
                    FinApiException error = (FinApiException) e;
                    assertThat(error.getOutcome()).isEqualTo(FinApiOutcome.REJECTED);
                    assertThat(error.getErrorCode()).isEqualTo(ErrorCode.FIN_API_ERROR);
                    assertThat(error.getFinCode()).isEqualTo(finCode);
                    assertThat(error.getDetails()).containsEntry("finCode", finCode);
                });
    }

    private static void assertFailure(Runnable call, FinApiOutcome outcome, ErrorCode errorCode) {
        assertThatThrownBy(call::run)
                .isInstanceOf(FinApiException.class)
                .satisfies(e -> {
                    FinApiException error = (FinApiException) e;
                    assertThat(error.getOutcome()).isEqualTo(outcome);
                    assertThat(error.getErrorCode()).isEqualTo(errorCode);
                    assertThat(error.getFinCode()).isNull();
                });
    }
}
