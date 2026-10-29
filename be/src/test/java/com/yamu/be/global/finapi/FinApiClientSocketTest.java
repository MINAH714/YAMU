package com.yamu.be.global.finapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import jakarta.validation.Validation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.web.client.RestClient;

/**
 * MockRestServiceServer는 예외를 직접 넣어 보므로, 실제 JDK HttpClient가 어떤 예외를 내는지는 확인하지 못한다.
 * 여기서는 {@link FinApiConfig}와 같은 구성으로 로컬 소켓에 붙어 "요청 안 감"과 "결과 불명"이 실제로도 나뉘는지 본다.
 * Spring·JDK 버전이 바뀌어 예외 종류가 달라지면 이 테스트가 먼저 깨진다. 시간 제한은 운영처럼 연결 제한을 응답 제한보다 짧게 두고 줄여서 본다.
 */
@Timeout(value = 15, unit = TimeUnit.SECONDS)
class FinApiClientSocketTest {

    private static final String BALANCE = "/edu/demandDeposit/inquireDemandDepositAccountBalance";

    private final List<AutoCloseable> opened = new CopyOnWriteArrayList<>();

    @AfterEach
    void tearDown() throws Exception {
        for (AutoCloseable c : opened) {
            c.close();
        }
    }

    @Test
    void 연결_제한이_응답_제한보다_짧지_않으면_서버를_띄우지_않는다() {
        FinApiProperties properties = new FinApiProperties("http://127.0.0.1", "k", Duration.ofSeconds(3), Duration.ofSeconds(3));

        assertThatThrownBy(() -> new FinApiConfig().finApiClient(RestClient.builder(), properties,
                Jackson2ObjectMapperBuilder.json().build(), Clock.systemUTC(), mock(FinTransactionNoIssuer.class),
                Validation.buildDefaultValidatorFactory().getValidator()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 연결이_거부되면_요청이_안_간_것() throws IOException {
        int closedPort;
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = probe.getLocalPort();
        }

        // Windows는 거부된 연결을 몇 번 다시 시도하느라 연결 시간 초과로 끝날 수 있다. 어느 쪽이든 요청은 가지 않았다.
        assertOutcome(clientFor("http://127.0.0.1:" + closedPort), FinApiOutcome.NOT_SENT);
    }

    @Test
    void 라우팅되지_않는_주소면_요청이_안_간_것() {
        // 응답하지 않는 사설 주소. 망에 따라 연결 시간 초과 또는 도달 불가로 끝나며, 둘 다 요청이 가지 않은 것이다.
        assertOutcome(clientFor("http://10.255.255.1:81"), FinApiOutcome.NOT_SENT);
    }

    @Test
    void 연결한_뒤_응답이_없으면_결과_불명() throws IOException {
        int port = serve(socket -> {
            // 요청을 받고 아무것도 보내지 않는다
        });

        assertOutcome(clientFor("http://127.0.0.1:" + port), FinApiOutcome.UNKNOWN);
    }

    @Test
    void 헤더를_보낸_뒤_본문이_멈추면_결과_불명() throws IOException {
        int port = serve(socket -> {
            OutputStream out = socket.getOutputStream();
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 200\r\n\r\n{\"Header\":")
                    .getBytes(StandardCharsets.UTF_8));
            out.flush();
        });

        assertOutcome(clientFor("http://127.0.0.1:" + port), FinApiOutcome.UNKNOWN);
    }

    // ───────── helpers ─────────

    private FinApiClient clientFor(String baseUrl) {
        FinApiProperties properties = new FinApiProperties(baseUrl, "test-api-key", Duration.ofMillis(500), Duration.ofMillis(1500));
        FinTransactionNoIssuer issuer = mock(FinTransactionNoIssuer.class);
        given(issuer.issue()).willReturn("20261028093015000001");
        return new FinApiConfig().finApiClient(RestClient.builder(), properties, Jackson2ObjectMapperBuilder.json().build(),
                Clock.systemUTC(), issuer, Validation.buildDefaultValidatorFactory().getValidator());
    }

    private static void assertOutcome(FinApiClient client, FinApiOutcome outcome) {
        assertThatThrownBy(() -> client.post(BALANCE, "test-user-key", null, JsonNode.class))
                .isInstanceOf(FinApiException.class)
                .satisfies(e -> assertThat(((FinApiException) e).getOutcome()).isEqualTo(outcome));
    }

    /** 연결을 하나 받아 handler를 실행하고, 연결은 테스트가 끝날 때까지 열어 둔다. 받은 포트를 돌려준다. */
    private int serve(SocketHandler handler) throws IOException {
        ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        opened.add(server);
        Thread thread = new Thread(() -> {
            try {
                Socket socket = server.accept();
                opened.add(socket);
                handler.handle(socket);
            } catch (IOException ignored) {
                // 테스트가 끝나 소켓을 닫으면 여기로 온다
            }
        });
        thread.setDaemon(true);
        thread.start();
        return server.getLocalPort();
    }

    @FunctionalInterface
    private interface SocketHandler {

        void handle(Socket socket) throws IOException;
    }
}
