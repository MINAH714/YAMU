package com.yamu.be.payment.barcode;

import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.global.security.AuthUser;
import com.yamu.be.support.AuthUsers;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest
@AutoConfigureMockMvc
class BarcodeControllerTest {

    static final String TOKEN = "0482917305561938";
    static final AuthUser ME = new AuthUser(7L, 1L);

    @Autowired MockMvc mockMvc;
    @MockitoBean BarcodeService barcodes;

    static RequestPostProcessor loggedIn() {
        return AuthUsers.asUser(ME.userId(), ME.userDeviceId());
    }

    @Test
    void 발급하면_201과_공통_응답으로_감싼_바코드를_돌려준다() throws Exception {
        when(barcodes.issue(ME, "step-up")).thenReturn(new BarcodeService.Issued(
                TOKEN, TOKEN, "yamu://pay/" + TOKEN, BarcodeStatus.ISSUED,
                LocalDateTime.parse("2026-10-28T05:30:00"), LocalDateTime.parse("2026-10-28T05:31:00"), 3));

        mockMvc.perform(post("/api/payments/barcodes")
                        .with(loggedIn())
                        .header("X-Step-Up-Token", "step-up"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                // 앞자리 0이 있는 숫자 16자리 코드가 숫자가 아니라 문자열로, 길이 그대로 나간다
                .andExpect(jsonPath("$.data.barcodeToken").value(instanceOf(String.class)))
                .andExpect(jsonPath("$.data.barcodeToken").value(matchesPattern("^[0-9]{16}$")))
                .andExpect(jsonPath("$.data.barcodeToken").value(TOKEN))
                .andExpect(jsonPath("$.data.barcodeValue").value(TOKEN))
                .andExpect(jsonPath("$.data.qrValue").value("yamu://pay/" + TOKEN))
                .andExpect(jsonPath("$.data.status").value("ISSUED"))
                .andExpect(jsonPath("$.data.issuedAt").value("2026-10-28T05:30:00Z"))
                .andExpect(jsonPath("$.data.expiresAt").value("2026-10-28T05:31:00Z"))
                .andExpect(jsonPath("$.data.activeCardCount").value(3))
                .andExpect(jsonPath("$.error").value(nullValue()));
    }

    @Test
    void 로그인하지_않으면_예전_임시_X_User_Id_헤더가_있어도_401_UNAUTHORIZED() throws Exception {
        mockMvc.perform(post("/api/payments/barcodes")
                        .header("X-User-Id", "7")
                        .header("X-Step-Up-Token", "step-up"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        verifyNoInteractions(barcodes);
    }

    @Test
    void 로그인하지_않으면_조회도_401_UNAUTHORIZED() throws Exception {
        mockMvc.perform(get("/api/payments/barcodes/{token}", TOKEN))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        verifyNoInteractions(barcodes);
    }

    @Test
    void 본인_인증_토큰이_없으면_null로_넘기고_서비스가_거절하면_403_STEP_UP_REQUIRED() throws Exception {
        when(barcodes.issue(ME, null)).thenThrow(new BusinessException(ErrorCode.STEP_UP_REQUIRED));

        mockMvc.perform(post("/api/payments/barcodes").with(loggedIn()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.error.code").value("STEP_UP_REQUIRED"));
    }

    @Test
    void 이미_쓴_본인_인증_토큰도_403_STEP_UP_REQUIRED() throws Exception {
        when(barcodes.issue(ME, "used-token")).thenThrow(new BusinessException(ErrorCode.STEP_UP_REQUIRED));

        mockMvc.perform(post("/api/payments/barcodes")
                        .with(loggedIn())
                        .header("X-Step-Up-Token", "used-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("STEP_UP_REQUIRED"));
    }

    @Test
    void ACTIVE_카드가_없으면_409_NO_ACTIVE_CARD() throws Exception {
        when(barcodes.issue(ME, "step-up")).thenThrow(new BusinessException(ErrorCode.NO_ACTIVE_CARD));

        mockMvc.perform(post("/api/payments/barcodes")
                        .with(loggedIn())
                        .header("X-Step-Up-Token", "step-up"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.error.code").value("NO_ACTIVE_CARD"))
                .andExpect(jsonPath("$.error.message").isString());
    }

    @Test
    void 조회하면_상태와_비어_있는_payment를_돌려준다() throws Exception {
        when(barcodes.get(7L, TOKEN)).thenReturn(new BarcodeService.State(
                BarcodeStatus.EXPIRED, LocalDateTime.parse("2026-10-28T05:31:00"), null));

        mockMvc.perform(get("/api/payments/barcodes/{token}", TOKEN).with(loggedIn()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("EXPIRED"))
                .andExpect(jsonPath("$.data.expiresAt").value("2026-10-28T05:31:00Z"))
                .andExpect(jsonPath("$.data", hasKey("payment")))
                .andExpect(jsonPath("$.data.payment").value(nullValue()));
    }

    @Test
    void 없는_바코드를_조회하면_404_BARCODE_NOT_FOUND() throws Exception {
        when(barcodes.get(7L, TOKEN)).thenThrow(new BusinessException(ErrorCode.BARCODE_NOT_FOUND));

        mockMvc.perform(get("/api/payments/barcodes/{token}", TOKEN).with(loggedIn()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.error.code").value("BARCODE_NOT_FOUND"));
    }

    @Test
    void 조회는_로그인한_사용자_번호로_소유자를_확인한다() throws Exception {
        when(barcodes.get(7L, TOKEN)).thenReturn(new BarcodeService.State(
                BarcodeStatus.ISSUED, LocalDateTime.parse("2026-10-28T05:31:00"), null));

        mockMvc.perform(get("/api/payments/barcodes/{token}", TOKEN).with(AuthUsers.asUser(7L, 99L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ISSUED"));
        // 다른 사용자(8)로 로그인하면 서비스에 8이 전달되고, 서비스가 404로 거절한다(BarcodeServiceTest)
        when(barcodes.get(8L, TOKEN)).thenThrow(new BusinessException(ErrorCode.BARCODE_NOT_FOUND));
        mockMvc.perform(get("/api/payments/barcodes/{token}", TOKEN).with(AuthUsers.asUser(8L, 1L)))
                .andExpect(status().isNotFound());
    }
}
