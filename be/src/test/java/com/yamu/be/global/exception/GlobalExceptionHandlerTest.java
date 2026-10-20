package com.yamu.be.global.exception;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.yamu.be.global.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest
@Import(GlobalExceptionHandlerTest.TestController.class)
class GlobalExceptionHandlerTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void 성공_응답은_success와_data만_내려간다() throws Exception {
        mockMvc.perform(get("/test/ok"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.cardName").value("테스트 카드"))
                .andExpect(jsonPath("$.data.createdAt").value("2026-10-20T10:00:00"))
                .andExpect(jsonPath("$.code").doesNotExist())
                .andExpect(jsonPath("$.message").doesNotExist());
    }

    @Test
    void BusinessException은_ErrorCode의_상태와_코드로_응답한다() throws Exception {
        mockMvc.perform(get("/test/business"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("COMMON_NOT_FOUND"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void 요청_본문_검증_실패는_400과_필드별_사유를_내려준다() throws Exception {
        mockMvc.perform(post("/test/body").contentType(MediaType.APPLICATION_JSON).content("{\"cardName\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_PARAM"))
                .andExpect(jsonPath("$.errors[0].field").value("cardName"))
                .andExpect(jsonPath("$.errors[0].reason").isNotEmpty());
    }

    @Test
    void 쿼리_파라미터_검증_실패는_400과_파라미터별_사유를_내려준다() throws Exception {
        mockMvc.perform(get("/test/param").param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_PARAM"))
                .andExpect(jsonPath("$.errors[0].field").value("page"));
    }

    @Test
    void 필수_파라미터_누락은_400() throws Exception {
        mockMvc.perform(get("/test/param"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_PARAM"))
                .andExpect(jsonPath("$.message", containsString("page")));
    }

    @Test
    void 깨진_JSON은_400() throws Exception {
        mockMvc.perform(post("/test/body").contentType(MediaType.APPLICATION_JSON).content("{broken"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_PARAM"));
    }

    @Test
    void 경로_변수_타입_불일치는_400() throws Exception {
        mockMvc.perform(get("/test/cards/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_PARAM"))
                .andExpect(jsonPath("$.message", containsString("cardId")));
    }

    @Test
    void 없는_경로는_404() throws Exception {
        mockMvc.perform(get("/api/not-exists"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("COMMON_NOT_FOUND"));
    }

    @Test
    void 지원하지_않는_메서드는_405와_Allow_헤더() throws Exception {
        mockMvc.perform(post("/test/ok"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().exists("Allow"))
                .andExpect(jsonPath("$.code").value("COMMON_METHOD_NOT_ALLOWED"));
    }

    @Test
    void 지원하지_않는_Content_Type은_415() throws Exception {
        mockMvc.perform(post("/test/body").contentType(MediaType.TEXT_PLAIN).content("hello"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("COMMON_UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    void 예상하지_못한_예외는_500이고_원인을_노출하지_않는다() throws Exception {
        mockMvc.perform(get("/test/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("COMMON_SERVER_ERROR"))
                .andExpect(jsonPath("$.message").value(ErrorCode.COMMON_SERVER_ERROR.getMessage()));
    }

    record CardRequest(@NotBlank String cardName) {
    }

    record CardResponse(String cardName, LocalDateTime createdAt) {
    }

    @RestController
    @RequestMapping("/test")
    static class TestController {

        @GetMapping("/ok")
        ApiResponse<CardResponse> ok() {
            return ApiResponse.ok(new CardResponse("테스트 카드", LocalDateTime.of(2026, 10, 20, 10, 0)));
        }

        @GetMapping("/business")
        ApiResponse<Void> business() {
            throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
        }

        @PostMapping("/body")
        ApiResponse<Void> body(@Valid @RequestBody CardRequest request) {
            return ApiResponse.ok();
        }

        @GetMapping("/param")
        ApiResponse<Void> param(@RequestParam @Min(0) int page) {
            return ApiResponse.ok();
        }

        @GetMapping("/cards/{cardId}")
        ApiResponse<Void> card(@PathVariable Long cardId) {
            return ApiResponse.ok();
        }

        @GetMapping("/unexpected")
        ApiResponse<Void> unexpected() {
            throw new IllegalStateException("DB 비밀번호가 틀렸습니다 같은 내부 정보");
        }
    }
}
