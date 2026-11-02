package com.yamu.be.payment.bank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.global.finapi.FinApiClient;
import com.yamu.be.global.finapi.FinApiException;
import com.yamu.be.global.finapi.FinApiOutcome;
import com.yamu.be.global.finapi.FinApiProperties;
import com.yamu.be.global.finapi.FinTransactionNoIssuer;
import com.yamu.be.payment.bank.TransferResult.RejectReason;
import com.yamu.be.payment.bank.TransferResult.Status;
import jakarta.validation.Validation;
import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * 금융망을 실제로 부르지 않고 MockRestServiceServer로 출금·입금·거래내역 응답을 흉내 낸다.
 * 공통 클라이언트({@link FinApiClient})는 진짜를 써서, 실패 분류가 두 클래스를 거쳐 어떻게 끝나는지 본다.
 */
@ExtendWith(OutputCaptureExtension.class)
class SsafyTransferApiTest {

    private static final String BASE = "https://fin.test/ssafy/api/v1";
    private static final String API_KEY = "test-api-key";
    private static final String USER_KEY = "test-user-key";
    private static final String ACCOUNT_NO = "0885170441365934";
    /** 2026-11-02 00:30 UTC = 한국 11월 2일 09:30 */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-02T00:30:00Z"), ZoneOffset.UTC);
    /** 한국 11월 1일 23:59:59에 만든 번호. 자정 직전 요청이 SSAFY에서 다음 날로 찍히는 경우를 본다 */
    private static final String TX = "20261101235959000001";

    MockRestServiceServer server;
    SsafyTransferApi api;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();
        FinApiProperties properties = new FinApiProperties(BASE, API_KEY, Duration.ofSeconds(3), Duration.ofSeconds(10));
        // 거래내역 조회는 조회할 때마다 새 번호를 받는다. 채번 자체는 FinTransactionNoIssuerTest에서 본다
        FinTransactionNoIssuer issuer = mock(FinTransactionNoIssuer.class);
        given(issuer.issue()).willReturn("20261102093000000001");
        FinApiClient client = new FinApiClient(builder.build(), properties, objectMapper, CLOCK, issuer,
                Validation.buildDefaultValidatorFactory().getValidator());
        api = new SsafyTransferApi(client, CLOCK);
    }

    // ───────── 출금·입금 성공 ─────────

    @Test
    void 출금은_미리_저장한_번호와_금액_문자열과_태그로_보내고_거래_번호와_거래일을_돌려준다() {
        server.expect(requestTo(BASE + SsafyTransferApi.WITHDRAW_PATH))
                .andExpect(jsonPath("$.Header.apiName").value("updateDemandDepositAccountWithdrawal"))
                .andExpect(jsonPath("$.Header.institutionTransactionUniqueNo").value(TX))
                .andExpect(jsonPath("$.Header.userKey").value(USER_KEY))
                .andExpect(jsonPath("$.accountNo").value(ACCOUNT_NO))
                .andExpect(jsonPath("$.transactionBalance").value("87000"))
                .andExpect(jsonPath("$.transactionSummary").value("YAMU-B88"))
                .andRespond(withSuccess(success("{\"transactionUniqueNo\":\"1101\",\"transactionDate\":\"20261102\"}"),
                        MediaType.APPLICATION_JSON));

        TransferResult result = api.withdraw(USER_KEY, ACCOUNT_NO, 87_000, TransferTag.bill(88), TX);

        assertThat(result).isEqualTo(new TransferResult(Status.SUCCESS, "1101", LocalDate.of(2026, 11, 2), null, null));
        server.verify();
    }

    @Test
    void 입금도_같은_방식이고_체크카드_환불은_C_태그다() {
        server.expect(requestTo(BASE + SsafyTransferApi.DEPOSIT_PATH))
                .andExpect(jsonPath("$.Header.apiName").value("updateDemandDepositAccountDeposit"))
                .andExpect(jsonPath("$.Header.institutionTransactionUniqueNo").value(TX))
                .andExpect(jsonPath("$.transactionBalance").value("14250"))
                .andExpect(jsonPath("$.transactionSummary").value("YAMU-C1025"))
                .andRespond(withSuccess(success("{\"transactionUniqueNo\":\"1152\",\"transactionDate\":\"20261102\"}"),
                        MediaType.APPLICATION_JSON));

        TransferResult result = api.deposit(USER_KEY, ACCOUNT_NO, 14_250, TransferTag.checkRefund(1025), TX);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.bankTransactionNo()).isEqualTo("1152");
        server.verify();
    }

    // ───────── 거절: 돈이 움직이지 않았다 ─────────

    @Test
    void 잔액_부족_A1014는_거절이다() {
        respondWithdraw(HttpStatus.BAD_REQUEST, "A1014");

        TransferResult result = api.withdraw(USER_KEY, ACCOUNT_NO, 87_000, TransferTag.payment(7), TX);

        assertThat(result.status()).isEqualTo(Status.REJECTED);
        assertThat(result.rejectReason()).isEqualTo(RejectReason.INSUFFICIENT_BALANCE);
        assertThat(result.finCode()).isEqualTo("A1014");
    }

    @Test
    void 계좌_없음_A1003은_거절이다() {
        respondWithdraw(HttpStatus.BAD_REQUEST, "A1003");

        TransferResult result = api.withdraw(USER_KEY, ACCOUNT_NO, 87_000, TransferTag.payment(7), TX);

        assertThat(result.status()).isEqualTo(Status.REJECTED);
        assertThat(result.rejectReason()).isEqualTo(RejectReason.ACCOUNT_NOT_FOUND);
    }

    @Test
    void 그_밖의_오류_코드는_코드를_담은_거절이다() {
        respondWithdraw(HttpStatus.BAD_REQUEST, "A1011");

        TransferResult result = api.withdraw(USER_KEY, ACCOUNT_NO, 87_000, TransferTag.payment(7), TX);

        assertThat(result.status()).isEqualTo(Status.REJECTED);
        assertThat(result.rejectReason()).isEqualTo(RejectReason.OTHER);
        assertThat(result.finCode()).isEqualTo("A1011");
    }

    // ───────── 결과 불명: 돈이 나갔을 수 있다 ─────────

    @Test
    void 요청_번호_중복_H1007은_거절이_아니라_결과_불명이다() {
        // 같은 번호의 요청이 이미 처리됐을 수 있다. 거절로 보면 실제로 나간 돈을 놓친다
        respondWithdraw(HttpStatus.BAD_REQUEST, "H1007");

        TransferResult result = api.withdraw(USER_KEY, ACCOUNT_NO, 87_000, TransferTag.payment(7), TX);

        assertThat(result.status()).isEqualTo(Status.UNKNOWN);
        assertThat(result.finCode()).isEqualTo("H1007");
    }

    @Test
    void 서버_오류는_오류_코드가_있어도_결과_불명이다() {
        respondWithdraw(HttpStatus.INTERNAL_SERVER_ERROR, "A1014");

        assertThat(api.withdraw(USER_KEY, ACCOUNT_NO, 87_000, TransferTag.payment(7), TX).status())
                .isEqualTo(Status.UNKNOWN);
    }

    @Test
    void 응답_시간_초과는_결과_불명이다() {
        server.expect(requestTo(BASE + SsafyTransferApi.WITHDRAW_PATH))
                .andRespond(withException(new HttpTimeoutException("request timed out")));

        assertThat(api.withdraw(USER_KEY, ACCOUNT_NO, 87_000, TransferTag.payment(7), TX).status())
                .isEqualTo(Status.UNKNOWN);
    }

    @Test
    void 성공_코드인데_거래_번호나_거래일이_없거나_틀리면_결과_불명이다() {
        for (String rec : List.of("{\"transactionDate\":\"20261102\"}", "{\"transactionUniqueNo\":\"1101\"}",
                "{\"transactionUniqueNo\":\"1101\",\"transactionDate\":\"20261399\"}")) {
            server.reset();
            server.expect(requestTo(BASE + SsafyTransferApi.WITHDRAW_PATH))
                    .andRespond(withSuccess(success(rec), MediaType.APPLICATION_JSON));

            assertThat(api.withdraw(USER_KEY, ACCOUNT_NO, 87_000, TransferTag.payment(7), TX).status())
                    .as(rec).isEqualTo(Status.UNKNOWN);
        }
    }

    // ───────── 안 보냄: 다시 보내도 된다 ─────────

    @Test
    void 연결_실패는_요청이_가지_않은_것이다() {
        server.expect(requestTo(BASE + SsafyTransferApi.WITHDRAW_PATH))
                .andRespond(withException(new ConnectException("Connection refused")));

        assertThat(api.withdraw(USER_KEY, ACCOUNT_NO, 87_000, TransferTag.payment(7), TX).status())
                .isEqualTo(Status.NOT_SENT);
    }

    // ───────── 잘못된 입력은 보내기 전에 막는다 ─────────

    @Test
    void 금액이_0원_이하면_보내지_않는다() {
        // 기대한 요청이 없으므로 요청이 나가면 MockRestServiceServer가 실패시킨다
        assertThatThrownBy(() -> api.withdraw(USER_KEY, ACCOUNT_NO, 0, TransferTag.payment(7), TX))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> api.deposit(USER_KEY, ACCOUNT_NO, -1, TransferTag.refund(7), TX))
                .isInstanceOf(IllegalArgumentException.class);
        server.verify();
    }

    @Test
    void 방향이_다른_태그나_빈_값이나_틀린_번호면_보내지_않는다() {
        assertThatThrownBy(() -> api.withdraw(USER_KEY, ACCOUNT_NO, 1000, TransferTag.refund(7), TX))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> api.deposit(USER_KEY, ACCOUNT_NO, 1000, TransferTag.payment(7), TX))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> api.withdraw(USER_KEY, ACCOUNT_NO, 1000, null, TX))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> api.withdraw(" ", ACCOUNT_NO, 1000, TransferTag.payment(7), TX))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> api.withdraw(USER_KEY, null, 1000, TransferTag.payment(7), TX))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> api.withdraw(USER_KEY, ACCOUNT_NO, 1000, TransferTag.payment(7), "123"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> api.withdraw(USER_KEY, ACCOUNT_NO, 1000, TransferTag.payment(7), null))
                .isInstanceOf(IllegalArgumentException.class);
        server.verify();
    }

    // ───────── 거래내역 조회 ─────────

    @Test
    void 거래내역은_기간과_방향으로_오래된_순으로_조회하고_한_줄씩_바꾼다() {
        server.expect(requestTo(BASE + SsafyTransferApi.HISTORY_PATH))
                .andExpect(jsonPath("$.Header.apiName").value("inquireTransactionHistoryList"))
                .andExpect(jsonPath("$.accountNo").value(ACCOUNT_NO))
                .andExpect(jsonPath("$.startDate").value("20261001"))
                .andExpect(jsonPath("$.endDate").value("20261022"))
                .andExpect(jsonPath("$.transactionType").value("D"))
                .andExpect(jsonPath("$.orderByType").value("ASC"))
                .andRespond(withSuccess(history("1", row("1024", "20261022", "143105", "2", "14250", "1235750",
                        "YAMU-P1025")), MediaType.APPLICATION_JSON));

        List<BankTransaction> rows = api.history(USER_KEY, ACCOUNT_NO, LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 10, 22), BankDirection.WITHDRAWAL);

        assertThat(rows).containsExactly(new BankTransaction("1024", LocalDate.of(2026, 10, 22), LocalTime.of(14, 31, 5),
                BankDirection.WITHDRAWAL, 14_250, 1_235_750, "YAMU-P1025"));
        assertThat(rows.get(0).tag()).contains(TransferTag.payment(1025));
        server.verify();
    }

    @Test
    void 방향을_고르지_않으면_전체로_조회하고_YAMU가_보내지_않은_거래도_읽는다() {
        server.expect(requestTo(BASE + SsafyTransferApi.HISTORY_PATH))
                .andExpect(jsonPath("$.transactionType").value("A"))
                .andRespond(withSuccess(history("1", row("1003", "20261001", "090000", "1", "1250000", "1250000",
                        "초기 입금")), MediaType.APPLICATION_JSON));

        List<BankTransaction> rows = api.history(USER_KEY, ACCOUNT_NO, LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 10, 1), null);

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.direction()).isEqualTo(BankDirection.DEPOSIT);
            assertThat(row.tag()).isEmpty();
        });
    }

    @Test
    void 모양이_틀린_줄이_하나라도_있으면_목록_전체를_결과_불명으로_본다() {
        // 그 줄을 빼고 읽으면 그 줄에 있던 태그를 "없다"로 잘못 판단할 수 있다
        server.expect(requestTo(BASE + SsafyTransferApi.HISTORY_PATH))
                .andRespond(withSuccess(history("2",
                        row("1024", "20261022", "143105", "2", "14250", "1235750", "YAMU-P1"),
                        row("1025", "20261022", "143106", "2", "만원", "1225750", "YAMU-P2")),
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> api.history(USER_KEY, ACCOUNT_NO, LocalDate.of(2026, 10, 22),
                LocalDate.of(2026, 10, 22), null))
                .isInstanceOf(FinApiException.class)
                .satisfies(e -> assertThat(((FinApiException) e).getOutcome()).isEqualTo(FinApiOutcome.UNKNOWN));
    }

    @Test
    void 달력에_없는_거래일이면_결과_불명이다() {
        server.expect(requestTo(BASE + SsafyTransferApi.HISTORY_PATH))
                .andRespond(withSuccess(history("1", row("1024", "20261399", "143105", "2", "14250", "1235750",
                        "YAMU-P1")), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> api.history(USER_KEY, ACCOUNT_NO, LocalDate.of(2026, 10, 22),
                LocalDate.of(2026, 10, 22), null))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.FIN_API_UNAVAILABLE));
    }

    @Test
    void 조회_기간이_거꾸로면_보내지_않는다() {
        assertThatThrownBy(() -> api.history(USER_KEY, ACCOUNT_NO, LocalDate.of(2026, 10, 2),
                LocalDate.of(2026, 10, 1), null))
                .isInstanceOf(IllegalArgumentException.class);
        server.verify();
    }

    // ───────── 태그로 결과 확정 ─────────

    @Test
    void 요청한_날_전날부터_다음_날까지_같은_방향만_조회해_태그가_정확히_같은_거래를_찾는다() {
        // 번호는 11월 1일, 오늘은 11월 2일. YAMU-P10은 YAMU-P1로 시작하지만 다른 결제다
        server.expect(requestTo(BASE + SsafyTransferApi.HISTORY_PATH))
                .andExpect(jsonPath("$.startDate").value("20261031"))
                .andExpect(jsonPath("$.endDate").value("20261102"))
                .andExpect(jsonPath("$.transactionType").value("D"))
                .andRespond(withSuccess(history("3",
                        row("1101", "20261101", "235958", "2", "5000", "100000", "YAMU-P10"),
                        row("1102", "20261102", "000001", "2", "14250", "85750", "YAMU-P1"),
                        row("1103", "20261102", "000002", "2", "3000", "82750", "YAMU-B1")),
                        MediaType.APPLICATION_JSON));

        TagLookup lookup = api.findByTag(USER_KEY, ACCOUNT_NO, TransferTag.payment(1), 14_250, TX);

        assertThat(lookup.result()).isEqualTo(TagLookup.Result.FOUND);
        assertThat(lookup.found().bankTransactionNo()).isEqualTo("1102");
        server.verify();
    }

    @Test
    void 확인이_늦어져도_조회_기간은_요청한_날_다음_날까지다() {
        // 10월 20일 요청을 11월 2일에 확인해도 10월 19일~21일만 본다. 기간이 늘면 거래가 많은 계좌에서 목록이 잘린다
        server.expect(requestTo(BASE + SsafyTransferApi.HISTORY_PATH))
                .andExpect(jsonPath("$.startDate").value("20261019"))
                .andExpect(jsonPath("$.endDate").value("20261021"))
                .andRespond(withSuccess(history("0"), MediaType.APPLICATION_JSON));

        TagLookup lookup = api.findByTag(USER_KEY, ACCOUNT_NO, TransferTag.payment(1), 5_000, "20261020120000000001");

        assertThat(lookup.result()).isEqualTo(TagLookup.Result.NOT_FOUND);
        server.verify();
    }

    @Test
    void 요청한_날_바로_확인하면_끝_날짜는_오늘이다() {
        // SSAFY가 미래 끝 날짜를 받는지 확인하지 못해 오늘을 넘기지 않는다. 다음 날로 찍힌 거래는 유예 시간 뒤 다시 확인할 때 보인다
        server.expect(requestTo(BASE + SsafyTransferApi.HISTORY_PATH))
                .andExpect(jsonPath("$.startDate").value("20261101"))
                .andExpect(jsonPath("$.endDate").value("20261102"))
                .andRespond(withSuccess(history("1", row("1102", "20261102", "092959", "2", "5000", "95000", "YAMU-P1")),
                        MediaType.APPLICATION_JSON));

        TagLookup lookup = api.findByTag(USER_KEY, ACCOUNT_NO, TransferTag.payment(1), 5_000, "20261102092958000001");

        assertThat(lookup.result()).isEqualTo(TagLookup.Result.FOUND);
        server.verify();
    }

    @Test
    void 같은_태그가_없으면_처리되지_않은_것이다() {
        respondHistory(history("2",
                row("1101", "20261101", "120000", "2", "5000", "100000", "YAMU-P10"),
                row("1102", "20261101", "120001", "2", "5000", "95000", "급여")));

        TagLookup lookup = api.findByTag(USER_KEY, ACCOUNT_NO, TransferTag.payment(1), 5_000, TX);

        assertThat(lookup.result()).isEqualTo(TagLookup.Result.NOT_FOUND);
        assertThat(lookup.matches()).isEmpty();
        assertThatThrownBy(lookup::found).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 태그는_같은데_금액이_다르면_금액_불일치다() {
        respondHistory(history("1", row("1102", "20261102", "000001", "2", "14000", "85750", "YAMU-P1")));

        TagLookup lookup = api.findByTag(USER_KEY, ACCOUNT_NO, TransferTag.payment(1), 14_250, TX);

        assertThat(lookup.result()).isEqualTo(TagLookup.Result.AMOUNT_MISMATCH);
        assertThat(lookup.matches()).hasSize(1);
    }

    @Test
    void 같은_태그가_두_건이면_두_번_처리된_것이다() {
        respondHistory(history("2",
                row("1102", "20261102", "000001", "2", "14250", "85750", "YAMU-P1"),
                row("1104", "20261102", "000005", "2", "14250", "71500", "YAMU-P1")));

        TagLookup lookup = api.findByTag(USER_KEY, ACCOUNT_NO, TransferTag.payment(1), 14_250, TX);

        assertThat(lookup.result()).isEqualTo(TagLookup.Result.DUPLICATED);
        assertThat(lookup.matches()).extracting(BankTransaction::bankTransactionNo).containsExactly("1102", "1104");
    }

    @Test
    void 입금_태그는_입금으로_조회하고_방향이_다른_같은_글자는_세지_않는다() {
        server.expect(requestTo(BASE + SsafyTransferApi.HISTORY_PATH))
                .andExpect(jsonPath("$.transactionType").value("M"))
                .andRespond(withSuccess(history("1", row("1102", "20261102", "000001", "2", "14250", "85750",
                        "YAMU-R9")), MediaType.APPLICATION_JSON));

        TagLookup lookup = api.findByTag(USER_KEY, ACCOUNT_NO, TransferTag.refund(9), 14_250, TX);

        assertThat(lookup.result()).isEqualTo(TagLookup.Result.NOT_FOUND);
    }

    @Test
    void 요약_앞뒤_공백은_무시한다() {
        respondHistory(history("1", row("1102", "20261102", "000001", "2", "14250", "85750", " YAMU-P1 ")));

        assertThat(api.findByTag(USER_KEY, ACCOUNT_NO, TransferTag.payment(1), 14_250, TX).result())
                .isEqualTo(TagLookup.Result.FOUND);
    }

    @Test
    void 목록이_잘려_와서_못_찾았으면_없다고_단정하지_않는다() {
        // totalCount가 받은 줄 수보다 크면 나머지에 그 태그가 있을 수 있다
        respondHistory(history("5", row("1101", "20261101", "120000", "2", "5000", "100000", "YAMU-P10")));

        assertThatThrownBy(() -> api.findByTag(USER_KEY, ACCOUNT_NO, TransferTag.payment(1), 5_000, TX))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.FIN_API_UNAVAILABLE));
    }

    @Test
    void 목록이_잘려_왔으면_태그를_찾았어도_판단하지_않는다() {
        // 빠진 4줄에 같은 태그가 더 있으면 FOUND가 아니라 DUPLICATED다
        respondHistory(history("5", row("1102", "20261102", "000001", "2", "5000", "95000", "YAMU-P1")));

        assertThatThrownBy(() -> api.findByTag(USER_KEY, ACCOUNT_NO, TransferTag.payment(1), 5_000, TX))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.FIN_API_UNAVAILABLE));
    }

    @Test
    void totalCount가_없거나_숫자가_아니면_잘린_목록과_같이_판단하지_않는다() {
        for (String rec : List.of("{\"list\":[]}", "{\"totalCount\":null,\"list\":[]}",
                "{\"totalCount\":\"abc\",\"list\":[]}", "{\"totalCount\":\"-1\",\"list\":[]}")) {
            server.reset();
            respondHistory(success(rec));

            assertThatThrownBy(() -> api.findByTag(USER_KEY, ACCOUNT_NO, TransferTag.payment(1), 5_000, TX))
                    .as(rec)
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.FIN_API_UNAVAILABLE));
        }
    }

    @Test
    void 거래가_없어_list가_null이면_totalCount가_0일_때만_빈_목록이다() {
        respondHistory(success("{\"totalCount\":\"0\",\"list\":null}"));
        assertThat(api.findByTag(USER_KEY, ACCOUNT_NO, TransferTag.payment(1), 5_000, TX).result())
                .isEqualTo(TagLookup.Result.NOT_FOUND);

        for (String rec : List.of("{\"totalCount\":\"3\",\"list\":null}", "{\"list\":null}", "{\"totalCount\":\"x\"}")) {
            server.reset();
            respondHistory(success(rec));

            assertThatThrownBy(() -> api.history(USER_KEY, ACCOUNT_NO, LocalDate.of(2026, 11, 1),
                    LocalDate.of(2026, 11, 2), null))
                    .as(rec)
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.FIN_API_UNAVAILABLE));
        }
    }

    @Test
    void 조회가_실패하면_아무것도_확정하지_않고_예외를_던진다() {
        server.expect(requestTo(BASE + SsafyTransferApi.HISTORY_PATH))
                .andRespond(withException(new HttpTimeoutException("request timed out")));

        assertThatThrownBy(() -> api.findByTag(USER_KEY, ACCOUNT_NO, TransferTag.payment(1), 5_000, TX))
                .isInstanceOf(FinApiException.class)
                .satisfies(e -> assertThat(((FinApiException) e).getOutcome()).isEqualTo(FinApiOutcome.UNKNOWN));
    }

    @Test
    void 번호의_날짜가_잘못되면_조회하지_않는다() {
        assertThatThrownBy(() -> api.findByTag(USER_KEY, ACCOUNT_NO, TransferTag.payment(1), 5_000,
                "20261399000000000001")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> api.findByTag(USER_KEY, ACCOUNT_NO, TransferTag.payment(1), 5_000, null))
                .isInstanceOf(IllegalArgumentException.class);
        server.verify();
    }

    // ───────── 로그 ─────────

    @Test
    void 계좌번호와_userKey와_앱_키는_로그에_남기지_않는다(CapturedOutput output) {
        respondWithdraw(HttpStatus.BAD_REQUEST, "A1014");
        api.withdraw(USER_KEY, ACCOUNT_NO, 87_000, TransferTag.payment(4242), TX);
        server.reset();
        respondHistory(history("1", row("1102", "20261102", "000001", "2", "87000", "1000", "YAMU-P4242")));
        api.findByTag(USER_KEY, ACCOUNT_NO, TransferTag.payment(4242), 87_000, TX);

        // 로그가 실제로 잡히는지 먼저 확인한다. 아무것도 안 잡히면 아래 검사는 의미가 없다
        assertThat(output.getAll()).contains("YAMU-P4242").contains("A1014");
        assertThat(output.getAll()).doesNotContain(ACCOUNT_NO).doesNotContain(USER_KEY).doesNotContain(API_KEY);
    }

    @Test
    void 결과_불명의_응답코드는_거절_코드처럼_남기지_않는다(CapturedOutput output) {
        respondWithdraw(HttpStatus.INTERNAL_SERVER_ERROR, "A1014");
        api.withdraw(USER_KEY, ACCOUNT_NO, 87_000, TransferTag.payment(4343), TX);

        String line = output.getAll().lines().filter(l -> l.contains("YAMU-P4343")).findFirst().orElseThrow();
        assertThat(line).contains("UNKNOWN").contains("처리 여부 아님").contains("A1014").doesNotContain("code=A1014");
    }

    @Test
    void 요청과_거래_한_줄의_문자열에는_계좌번호와_거래_후_잔액이_없다() {
        String transfer = new SsafyTransferApi.TransferRequest(ACCOUNT_NO, "87000", "YAMU-P1").toString();
        String historyRequest = new SsafyTransferApi.HistoryRequest(ACCOUNT_NO, "20261101", "20261102", "D", "ASC")
                .toString();
        String historyRow = new SsafyTransferApi.HistoryRow("1102", "20261102", "000001", "2", "14250", "1235750",
                "YAMU-P1").toString();
        String transaction = new BankTransaction("1102", LocalDate.of(2026, 11, 2), LocalTime.of(0, 0, 1),
                BankDirection.WITHDRAWAL, 14_250, 1_235_750, "YAMU-P1").toString();

        assertThat(transfer).doesNotContain(ACCOUNT_NO).contains("****5934").contains("YAMU-P1");
        assertThat(historyRequest).doesNotContain(ACCOUNT_NO).contains("****5934").contains("20261101");
        assertThat(historyRow).doesNotContain("1235750").contains("14250");
        assertThat(transaction).doesNotContain("1235750").contains("14250");
        assertThat(SsafyTransferApi.maskAccount("123")).isEqualTo("****");
    }

    // ───────── 응답 만들기 ─────────

    private void respondWithdraw(HttpStatus status, String code) {
        server.expect(requestTo(BASE + SsafyTransferApi.WITHDRAW_PATH)).andRespond(withStatus(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"responseCode\":\"%s\",\"responseMessage\":\"오류\"}".formatted(code)));
    }

    private void respondHistory(String body) {
        server.expect(requestTo(BASE + SsafyTransferApi.HISTORY_PATH))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private static String success(String rec) {
        return """
                {"Header":{"responseCode":"H0000","responseMessage":"정상처리 되었습니다."},"REC":%s}
                """.formatted(rec);
    }

    private static String history(String totalCount, String... rows) {
        return success("{\"totalCount\":\"%s\",\"list\":[%s]}".formatted(totalCount, String.join(",", rows)));
    }

    private static String row(String no, String date, String time, String type, String amount, String after,
            String summary) {
        return """
                {"transactionUniqueNo":"%s","transactionDate":"%s","transactionTime":"%s","transactionType":"%s",
                 "transactionTypeName":"","transactionAccountNo":"","transactionBalance":"%s",
                 "transactionAfterBalance":"%s","transactionSummary":"%s","transactionMemo":""}
                """.formatted(no, date, time, type, amount, after, summary);
    }
}
