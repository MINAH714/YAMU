package com.yamu.be.global.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yamu.be.global.security.AccessTokenIssuer;
import com.yamu.be.global.security.ActiveSessionChecker;
import com.yamu.be.support.MySqlTestSupport;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 컨트롤러 밖(인증 필터)에서 난 오류도 공통 응답으로 나가는지 실제 서버로 확인한다(S15P31E103-369).
 * MockMvc는 서블릿 컨테이너의 {@code /error} 전달을 흉내 내지 않아서 포트를 띄운다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "spring.application.name=yamu-filter-error-test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FilterErrorResponseIntegrationTest extends MySqlTestSupport {

    @LocalServerPort int port;
    @Autowired AccessTokenIssuer accessTokenIssuer;
    @Autowired ObjectMapper objectMapper;
    @MockitoBean ActiveSessionChecker sessionChecker;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void 로그인_검사에서_DB가_끊기면_500_INTERNAL_ERROR_공통_응답이고_원인은_응답에_없다() throws Exception {
        when(sessionChecker.status(anyLong(), anyLong()))
                .thenThrow(new CannotGetJdbcConnectionException("Failed to obtain JDBC Connection: secret-host:3306"));
        String token = accessTokenIssuer.issue(1L, 1L).token();

        HttpResponse<String> response = get("/api/users/me", token);

        assertThat(response.statusCode()).isEqualTo(500);
        JsonNode body = objectMapper.readTree(response.body());
        assertThat(body.get("success").asBoolean()).isFalse();
        assertThat(body.get("data").isNull()).isTrue();
        assertThat(body.at("/error/code").asText()).isEqualTo("INTERNAL_ERROR");
        assertThat(body.has("timestamp")).isFalse();
        assertThat(response.body()).doesNotContain("secret-host", "JDBC", "Exception");
    }

    @Test
    void 기존_응답은_그대로다_토큰_없으면_401_없는_경로는_404() throws Exception {
        HttpResponse<String> noToken = get("/api/users/me", null);
        assertThat(noToken.statusCode()).isEqualTo(401);
        assertThat(objectMapper.readTree(noToken.body()).at("/error/code").asText()).isEqualTo("UNAUTHORIZED");

        HttpResponse<String> unknown = get("/api/health-not-here", null);
        assertThat(unknown.statusCode()).isIn(401, 404);
        assertThat(objectMapper.readTree(unknown.body()).get("success").asBoolean()).isFalse();
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
