package com.yamu.be.assetflow;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.yamu.be.global.security.AuthUser;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.global.exception.GlobalExceptionHandler;
import java.time.YearMonth;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.core.MethodParameter;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.bind.support.WebDataBinderFactory;

/** 파라미터 바인딩·응답 봉투 검사. 인증 필터 통합 검사는 별도 서버/DB 검사 범위다. */
class AssetFlowControllerTest {
    AssetFlowService service=mock(AssetFlowService.class);
    MockMvc mvc=MockMvcBuilders.standaloneSetup(new AssetFlowController(service))
            .setControllerAdvice(new GlobalExceptionHandler())
            .setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
                public boolean supportsParameter(MethodParameter p) { return p.getParameterType()==AuthUser.class; }
                public Object resolveArgument(MethodParameter p, ModelAndViewContainer m, NativeWebRequest r, WebDataBinderFactory b) {
                    AuthUser me=mock(AuthUser.class); when(me.userId()).thenReturn(7L); return me;
                }
            }).build();
    @Test void bindsMonthAndRepeatedAccountsAndRefresh() throws Exception {
        var point = new AssetFlowResponse.Point(LocalDate.of(2026,11,14), AssetFlowResponse.Kind.FORECAST,
                AssetFlowResponse.DataStatus.NO_DATA,null,null,null,List.of());
        when(service.get(anyLong(),any(),any(),anyBoolean())).thenReturn(new AssetFlowResponse(
                YearMonth.of(2026,11),List.of(1L,2L),0L,Instant.parse("2026-11-13T03:00:00Z"),
                List.of(point),null,null,List.of(),Instant.parse("2026-11-13T03:00:01Z")));
        mvc.perform(get("/api/asset-flows").param("yearMonth","2026-11").param("accountIds","1","2").param("refresh","true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.currentTotalBalance").value(0))
                .andExpect(jsonPath("$.data.series[0].kind").value("FORECAST"))
                .andExpect(jsonPath("$.data.series[0].dataStatus").value("NO_DATA"))
                .andExpect(jsonPath("$.data.series[0].displayBalance").isEmpty());
        verify(service).get(7L,YearMonth.of(2026,11),List.of(1L,2L),true);
    }
    @Test void defaultsArePassedToService() throws Exception {
        mvc.perform(get("/api/asset-flows")).andExpect(status().isOk());
        verify(service).get(7L,null,null,false);
    }
    @Test void malformedMonthIsRejectedBeforeService() throws Exception {
        mvc.perform(get("/api/asset-flows").param("yearMonth","2026-99")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @Test void midnightConflictHasDedicated409Code() throws Exception {
        when(service.get(7L,null,null,false)).thenThrow(new BusinessException(ErrorCode.FORECAST_DATE_CHANGED));
        mvc.perform(get("/api/asset-flows")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("FORECAST_DATE_CHANGED"));
    }
}
