package com.yamu.be.global.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * 공휴일 API를 실제로 부르지 않고 MockRestServiceServer로 요청 모양과 응답 처리를 본다.
 * 응답 예시는 공공데이터포털 「한국천문연구원_특일 정보」 활용가이드의 XML 형태를 따른다.
 */
@ExtendWith(OutputCaptureExtension.class)
class HolidayApiClientTest {

    private static final String BASE = "https://holiday.test/SpcdeInfoService";
    /** 디코딩 키처럼 +·/·=가 든 값. 보낼 때 한 번만 인코딩되어야 한다. */
    private static final String KEY = "test+key/abc==";

    MockRestServiceServer server;

    @Test
    void 한_달_응답에서_공휴일만_날짜와_이름으로_읽는다() {
        HolidayApiClient client = client(KEY);
        expectMonth(5, ok(item("20260501", "노동절", "Y"), item("20260505", "어린이날", "Y"),
                item("20260508", "어버이날", "N")));

        assertThat(client.fetchMonth(2026, 5)).containsExactly(
                Map.entry(LocalDate.of(2026, 5, 1), "노동절"),
                Map.entry(LocalDate.of(2026, 5, 5), "어린이날"));
        server.verify();
    }

    @Test
    void 서비스_키는_한_번만_인코딩하고_월은_두_자리로_명세의_필수_항목과_함께_보낸다() {
        HolidayApiClient client = client(KEY);
        server.expect(requestTo(containsString("/SpcdeInfoService/getRestDeInfo?")))
                .andExpect(requestTo(containsString("ServiceKey=test%2Bkey%2Fabc%3D%3D")))
                .andExpect(requestTo(containsString("solYear=2026")))
                .andExpect(requestTo(containsString("solMonth=05")))
                .andExpect(requestTo(containsString("numOfRows=100")))
                .andExpect(requestTo(containsString("pageNo=1")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(xml(ok()));

        assertThat(client.fetchMonth(2026, 5)).isEmpty();
        server.verify();
    }

    @Test
    void 인코딩_키를_넣어도_한_번_풀어서_디코딩_키와_똑같이_보낸다(CapturedOutput output) {
        HolidayApiClient client = client("test%2Bkey%2Fabc%3D%3D");
        server.expect(requestTo(containsString("ServiceKey=test%2Bkey%2Fabc%3D%3D&")))
                .andRespond(xml(ok()));

        assertThat(client.fetchMonth(2026, 5)).isEmpty();
        server.verify();
        assertThat(output.getAll()).contains("인코딩 키가 들어 있어 한 번 풀어서 씁니다").doesNotContain("test%2Bkey");
    }

    @Test
    void 키의_퍼센트_인코딩이_깨져_있으면_부르지_않고_키를_남기지_않고_실패한다() {
        HolidayApiClient client = client("test%zzkey");

        assertThatThrownBy(() -> client.fetchMonth(2026, 5))
                .isInstanceOf(HolidayApiException.class)
                .hasMessageContaining("% 인코딩이 깨져 있습니다")
                .message().doesNotContain("test%zzkey");
        server.verify();
    }

    @Test
    void 같은_날_공휴일_둘은_이름을_합쳐_하나로_둔다() {
        HolidayApiClient client = client(KEY);
        expectMonth(5, ok(item("20250505", "어린이날", "Y"), item("20250505", "부처님오신날", "Y")));

        assertThat(client.fetchMonth(2025, 5)).containsExactly(Map.entry(LocalDate.of(2025, 5, 5), "어린이날·부처님오신날"));
    }

    @Test
    void 일월부터_십이월까지_불러_한_해로_합친다() {
        HolidayApiClient client = client(KEY);
        for (int month = 1; month <= 12; month++) {
            expectMonth(month, month == 1 ? ok(item("20270101", "1월 1일", "Y"))
                    : month == 10 ? ok(item("20271003", "개천절", "Y"), item("20271009", "한글날", "Y"))
                    : ok());
        }

        assertThat(client.fetchYear(2027).keySet()).containsExactly(
                LocalDate.of(2027, 1, 1), LocalDate.of(2027, 10, 3), LocalDate.of(2027, 10, 9));
        server.verify();
    }

    @Test
    void 한_달이라도_실패하면_그_해_전체가_실패한다() {
        HolidayApiClient client = client(KEY);
        expectMonth(1, ok(item("20270101", "1월 1일", "Y")));
        server.expect(requestTo(containsString("solMonth=02"))).andRespond(withServerError());

        assertThatThrownBy(() -> client.fetchYear(2027))
                .isInstanceOf(HolidayApiException.class)
                .hasMessageContaining("2027-02")
                .hasMessageContaining("HTTP 500");
    }

    @Test
    void resultCode가_00이_아니면_실패한다() {
        HolidayApiClient client = client(KEY);
        expectMonth(5, """
                <response><header><resultCode>99</resultCode><resultMsg>ERROR</resultMsg></header></response>""");

        assertThatThrownBy(() -> client.fetchMonth(2026, 5))
                .isInstanceOf(HolidayApiException.class)
                .hasMessageContaining("resultCode=99");
    }

    @Test
    void 키_오류는_HTTP_200이어도_실패하고_사유_코드만_남긴다() {
        HolidayApiClient client = client(KEY);
        expectMonth(5, """
                <OpenAPI_ServiceResponse><cmmMsgHeader><errMsg>SERVICE ERROR</errMsg>
                <returnAuthMsg>SERVICE_KEY_IS_NOT_REGISTERED_ERROR</returnAuthMsg>
                <returnReasonCode>30</returnReasonCode></cmmMsgHeader></OpenAPI_ServiceResponse>""");

        assertThatThrownBy(() -> client.fetchMonth(2026, 5))
                .isInstanceOf(HolidayApiException.class)
                .hasMessageContaining("returnReasonCode=30")
                .hasNoCause()
                .message().doesNotContain(KEY).doesNotContain("test%2Bkey");
    }

    @Test
    void totalCount와_받은_항목_수가_다르면_빠진_날이_있을_수_있어_실패한다() {
        HolidayApiClient client = client(KEY);
        expectMonth(5, ok(item("20260505", "어린이날", "Y")).replace("<totalCount>1</totalCount>", "<totalCount>2</totalCount>"));

        assertThatThrownBy(() -> client.fetchMonth(2026, 5))
                .isInstanceOf(HolidayApiException.class)
                .hasMessageContaining("totalCount 2");
    }

    @Test
    void 요청하지_않은_달의_날짜가_오면_실패한다() {
        HolidayApiClient client = client(KEY);
        expectMonth(5, ok(item("20260606", "현충일", "Y")));

        assertThatThrownBy(() -> client.fetchMonth(2026, 5))
                .isInstanceOf(HolidayApiException.class)
                .hasMessageContaining("요청하지 않은 달의 날짜 2026-06-06");
    }

    @Test
    void XML이_아닌_응답은_내용을_남기지_않고_실패한다(CapturedOutput output) {
        HolidayApiClient client = client(KEY);
        server.expect(requestTo(containsString("solMonth=05")))
                .andRespond(withSuccess("Unexpected errors " + KEY, MediaType.TEXT_PLAIN));

        assertThatThrownBy(() -> client.fetchMonth(2026, 5))
                .isInstanceOf(HolidayApiException.class)
                .hasMessageContaining("XML이 아닌 응답")
                .message().doesNotContain("Unexpected");
        assertThat(output.getAll()).doesNotContain(KEY);
    }

    @Test
    void DOCTYPE이_든_응답은_외부_엔티티를_읽지_않고_실패한다() {
        HolidayApiClient client = client(KEY);
        expectMonth(5, """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE response [<!ENTITY xxe SYSTEM "file:///etc/hostname">]>
                <response><header><resultCode>00</resultCode></header>
                <body><items><item><dateName>&xxe;</dateName><isHoliday>Y</isHoliday><locdate>20260505</locdate></item></items>
                <totalCount>1</totalCount></body></response>""");

        assertThatThrownBy(() -> client.fetchMonth(2026, 5))
                .isInstanceOf(HolidayApiException.class)
                .hasMessageContaining("XML이 아닌 응답");
    }

    @Test
    void 키가_없으면_부르지_않고_실패한다() {
        HolidayApiClient client = client(" ");

        assertThatThrownBy(() -> client.fetchMonth(2026, 5))
                .isInstanceOf(HolidayApiException.class)
                .hasMessageContaining("서비스 키가 없습니다");
        server.verify();
    }

    /**
     * RestClient의 연결 실패 예외 메시지에는 키가 든 요청 주소가 그대로 들어간다.
     * 실제 JDK HttpClient로 닫힌 포트에 붙여, 바꿔 던진 예외와 로그에 키가 없는지 본다.
     */
    @Test
    void 연결에_실패해도_예외와_로그에_서비스_키가_남지_않는다(CapturedOutput output) throws IOException {
        int closedPort;
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = probe.getLocalPort();
        }
        HolidayApiProperties properties = new HolidayApiProperties("http://127.0.0.1:" + closedPort, KEY,
                Duration.ofMillis(500), Duration.ofMillis(1500), "0 0 4 * * *");
        HolidayApiClient client = new HolidaySyncConfig().holidayApiClient(RestClient.builder(), properties);

        assertThatThrownBy(() -> client.fetchMonth(2026, 5))
                .isInstanceOf(HolidayApiException.class)
                .hasMessageContaining("연결 실패")
                .hasNoCause()
                .message().doesNotContain(KEY).doesNotContain("test%2Bkey").doesNotContain("ServiceKey");
        assertThat(output.getAll()).doesNotContain("test%2Bkey").doesNotContain(KEY);
    }

    @Test
    void 설정을_문자열로_찍어도_키가_나오지_않는다() {
        HolidayApiProperties properties = new HolidayApiProperties(BASE, KEY, Duration.ofSeconds(3), Duration.ofSeconds(10),
                "0 0 4 * * *");

        assertThat(properties.toString()).doesNotContain(KEY).contains("serviceKey=***");
    }

    // ───────── helpers ─────────

    private HolidayApiClient client(String key) {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        HolidayApiProperties properties = new HolidayApiProperties(BASE, key, Duration.ofSeconds(3), Duration.ofSeconds(10),
                "0 0 4 * * *");
        return new HolidayApiClient(builder.build(), properties);
    }

    private void expectMonth(int month, String body) {
        server.expect(requestTo(containsString("solMonth=%02d".formatted(month)))).andRespond(xml(body));
    }

    private static org.springframework.test.web.client.ResponseCreator xml(String body) {
        return withSuccess(body, MediaType.TEXT_XML);
    }

    private static String ok(String... items) {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <response><header><resultCode>00</resultCode><resultMsg>NORMAL SERVICE.</resultMsg></header>
                <body><items>%s</items><numOfRows>100</numOfRows><pageNo>1</pageNo><totalCount>%d</totalCount></body></response>"""
                .formatted(Stream.of(items).collect(Collectors.joining()), items.length);
    }

    private static String item(String locdate, String name, String isHoliday) {
        return "<item><dateKind>01</dateKind><dateName>%s</dateName><isHoliday>%s</isHoliday><locdate>%s</locdate><seq>1</seq></item>"
                .formatted(name, isHoliday, locdate);
    }
}
