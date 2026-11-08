package com.yamu.be.forecast;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.yamu.be.account.SsafyAccountApi;
import com.yamu.be.support.AuthUsers;
import com.yamu.be.support.MySqlTestSupport;
import com.yamu.be.support.OwnershipFixtures;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

/**
 * 잔고예보·생활비 상세·가상 구매에 남의 계좌를 보내면 실제 경로에서 404인지 확인한다(S15P31E103-333).
 * 다른 컨트롤러 테스트는 잔액 서비스(BE 1 {@code AccountBalanceService})를 가짜로 바꿔 계좌 소유 확인을 지나치므로,
 * 여기서는 잔액 서비스를 그대로 두고 금융망 호출({@link SsafyAccountApi})만 가짜로 둔다. 소유 확인은 금융망을 부르기 전이어야 한다.
 */
@Transactional
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.application.name=yamu-forecast-ownership-test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ForecastAccountOwnershipTest extends MySqlTestSupport {
    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean SsafyAccountApi ssafy;
    @MockitoBean SimulationRecommender recommender;

    long owner;
    long myAccount;
    long myCheckCard;
    long strangerAccount;

    @BeforeEach
    void setUp() {
        OwnershipFixtures ownership = new OwnershipFixtures(jdbc);
        SpendingFixtures spending = new SpendingFixtures(jdbc);
        owner = ownership.user();
        myAccount = ownership.account(owner);
        myCheckCard = spending.card(owner, spending.product("CHECK"), myAccount,
                LocalDate.now(KOREA).minusDays(1));
        strangerAccount = ownership.account(ownership.user());
    }

    @Test
    void 잔고예보에_남의_계좌를_보내면_내_계좌와_섞여도_404이고_금융망을_부르지_않는다() throws Exception {
        expectNotFound(mockMvc.perform(get("/api/forecasts/balance").with(AuthUsers.asUser(owner))
                .param("accountIds", String.valueOf(strangerAccount))));
        expectNotFound(mockMvc.perform(get("/api/forecasts/balance").with(AuthUsers.asUser(owner))
                .param("accountIds", myAccount + "," + strangerAccount)));
        verifyNoInteractions(ssafy);
    }

    @Test
    void 생활비_상세에_남의_계좌를_보내면_내_계좌와_섞여도_404이고_금융망을_부르지_않는다() throws Exception {
        expectNotFound(mockMvc.perform(get("/api/forecasts/living-cost").with(AuthUsers.asUser(owner))
                .param("accountIds", String.valueOf(strangerAccount))));
        expectNotFound(mockMvc.perform(get("/api/forecasts/living-cost").with(AuthUsers.asUser(owner))
                .param("accountIds", myAccount + "," + strangerAccount)));
        verifyNoInteractions(ssafy);
    }

    @Test
    void 가상_구매에_남의_계좌를_보내면_내_카드여도_404이고_금융망을_부르지_않는다() throws Exception {
        expectNotFound(simulate("[" + strangerAccount + "]"));
        expectNotFound(simulate("[" + myAccount + ", " + strangerAccount + "]"));
        verifyNoInteractions(ssafy);
    }

    private ResultActions simulate(String accountIds) throws Exception {
        return mockMvc.perform(post("/api/forecasts/simulations").with(AuthUsers.asUser(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"accountIds\": " + accountIds + ", \"userCardId\": " + myCheckCard
                        + ", \"amount\": 300000, \"productCategoryCode\": \"PRODUCT_ELECTRONICS\","
                        + " \"paymentChannel\": \"OFFLINE\"}"));
    }

    private static void expectNotFound(ResultActions result) throws Exception {
        result.andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }
}
