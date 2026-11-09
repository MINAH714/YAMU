package com.yamu.be.cashflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.yamu.be.support.AuthUsers;
import com.yamu.be.support.MySqlTestSupport;
import com.yamu.be.support.OwnershipFixtures;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 가계부 항목 등록의 Idempotency-Key(S15P31E103-358, QA API-05)를 실제 MySQL·등록 API로 확인한다. 동시 요청이 각자 커밋해야
 * 하므로 테스트 트랜잭션(@Transactional)을 쓰지 않고, 테스트마다 새 사용자를 만든다.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.application.name=yamu-cashflow-idempotency-test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CashflowIdempotencyTest extends MySqlTestSupport {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired CashflowCreator creator;

    OwnershipFixtures fixtures;
    long owner;
    long account;
    LocalDate tomorrow;

    @BeforeEach
    void setUp() {
        fixtures = new OwnershipFixtures(jdbc);
        owner = fixtures.user();
        account = fixtures.account(owner);
        tomorrow = LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(1);
    }

    @Test
    void 같은_키로_다시_보내면_새로_만들지_않고_처음_항목을_돌려준다() throws Exception {
        String key = UUID.randomUUID().toString();

        String first = create(owner, key, body(10_000L)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String second = create(owner, key, body(10_000L)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        assertThat(id(second)).isEqualTo(id(first));
        assertThat(items(owner)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cashflow_occurrence WHERE user_id = ?", Long.class,
                owner)).isEqualTo(1L);
    }

    @Test
    void 대문자_키도_같은_키로_본다() throws Exception {
        String key = UUID.randomUUID().toString();

        create(owner, key, body(10_000L)).andExpect(status().isCreated());
        create(owner, key.toUpperCase(), body(10_000L)).andExpect(status().isCreated());

        assertThat(items(owner)).isEqualTo(1L);
    }

    @Test
    void 같은_키로_다른_내용을_보내면_409이고_만들지_않는다() throws Exception {
        String key = UUID.randomUUID().toString();
        create(owner, key, body(10_000L)).andExpect(status().isCreated());

        create(owner, key, body(20_000L))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));

        assertThat(items(owner)).isEqualTo(1L);
    }

    @Test
    void 지운_항목의_키로_늦게_다시_보내면_다시_만들지_않고_404다() throws Exception {
        // Codex 검토: 시작 전 항목을 지우면 규칙 행째 사라져, 키를 그 행에 두면 늦은 재시도가 항목을 다시 만들었다.
        String key = UUID.randomUUID().toString();
        long id = id(create(owner, key, body(10_000L)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        mockMvc.perform(delete("/api/cashflow-items/" + id).with(AuthUsers.asUser(owner)))
                .andExpect(status().isNoContent());
        assertThat(items(owner)).isZero();

        create(owner, key, body(10_000L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));

        assertThat(items(owner)).isZero();
    }

    @Test
    void 고친_뒤_같은_키로_다시_보내면_새로_만들지_않고_지금_값을_돌려준다() throws Exception {
        // 계약(2026-11-09 결정): 처음 응답의 복제가 아니라 그때 만든 규칙의 지금 값이다.
        String key = UUID.randomUUID().toString();
        long id = id(create(owner, key, body(10_000L)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        mockMvc.perform(patch("/api/cashflow-items/" + id).with(AuthUsers.asUser(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{ \"title\": \"요가\" }"))
                .andExpect(status().isOk());

        create(owner, key, body(10_000L))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.cashflowItemId").value(id))
                .andExpect(jsonPath("$.data.title").value("요가"));

        assertThat(items(owner)).isEqualTo(1L);
    }

    @Test
    void 칸_경계를_흉내_낸_서로_다른_내용은_지문이_다르다() {
        // Codex 검토: 레코드 toString은 쉼표·등호를 이스케이프하지 않아 아래 두 요청이 같은 문자열이 됐다.
        CashflowService.CreateCommand first = command("a, memo=b", "c");
        CashflowService.CreateCommand second = command("a", "b, memo=c");

        assertThat(first.toString()).isEqualTo(second.toString());
        assertThat(CashflowService.fingerprint(first)).isNotEqualTo(CashflowService.fingerprint(second));
        assertThat(CashflowService.fingerprint(first)).isEqualTo(CashflowService.fingerprint(command("a, memo=b", "c")));
    }

    @Test
    void 키가_없으면_전처럼_매번_새로_만든다() throws Exception {
        create(owner, null, body(10_000L)).andExpect(status().isCreated());
        create(owner, null, body(10_000L)).andExpect(status().isCreated());

        assertThat(items(owner)).isEqualTo(2L);
    }

    @Test
    void UUID가_아닌_키는_400이고_만들지_않는다() throws Exception {
        create(owner, "not-a-uuid", body(10_000L))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REQUIRED"));

        assertThat(items(owner)).isZero();
    }

    @Test
    void 다른_사용자의_같은_키는_서로_상관없다() throws Exception {
        long stranger = fixtures.user();
        long strangerAccount = fixtures.account(stranger);
        String key = UUID.randomUUID().toString();

        create(owner, key, body(10_000L)).andExpect(status().isCreated());
        // 남의 키를 알아도 그 사람 항목이 돌아오거나 409로 키 사용 여부가 드러나지 않는다.
        create(stranger, key, """
                { "type": "EXPENSE", "title": "헬스장", "amount": 20000, "date": "%s", "accountId": %d }
                """.formatted(tomorrow, strangerAccount))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.accountId").value(strangerAccount));

        assertThat(items(owner)).isEqualTo(1L);
        assertThat(items(stranger)).isEqualTo(1L);
    }

    @Test
    void 같은_키의_첫_요청이_동시에_와도_하나만_만들고_모두_같은_항목을_돌려준다() throws Exception {
        // QA API-05: 같은 본문 동시 3번 + 순차 1번에 201이 네 번 오고 예정 지출이 4개 생겼다.
        String key = UUID.randomUUID().toString();
        CashflowService.CreateCommand command = new CashflowService.CreateCommand("헬스장",
                CashflowItem.Direction.EXPENSE, 10_000L, tomorrow, CashflowItem.RecurrenceType.NONE, null, null, null,
                account, null, null, null);

        ExecutorService pool = Executors.newFixedThreadPool(3);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<CashflowService.Item>> results = new ArrayList<>();
        try {
            for (int i = 0; i < 3; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return creator.create(owner, command, key);
                }));
            }
            start.countDown();
            List<Long> ids = new ArrayList<>();
            for (Future<CashflowService.Item> result : results) {
                ids.add(result.get(30, TimeUnit.SECONDS).id());
            }
            ids.add(creator.create(owner, command, key).id());
            assertThat(ids).containsOnly(ids.get(0));
        } finally {
            pool.shutdownNow();
        }

        assertThat(items(owner)).isEqualTo(1L);
    }

    private ResultActions create(long user, String key, String body) throws Exception {
        MockHttpServletRequestBuilder request = post("/api/cashflow-items").with(AuthUsers.asUser(user))
                .contentType(MediaType.APPLICATION_JSON).content(body);
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return mockMvc.perform(request);
    }

    private CashflowService.CreateCommand command(String merchantCategoryCode, String memo) {
        return new CashflowService.CreateCommand("헬스장", CashflowItem.Direction.EXPENSE, 10_000L, tomorrow,
                CashflowItem.RecurrenceType.NONE, null, null, null, account, null, merchantCategoryCode, memo);
    }

    private String body(long amount) {
        return """
                { "type": "EXPENSE", "title": "헬스장", "amount": %d, "date": "%s", "accountId": %d }
                """.formatted(amount, tomorrow, account);
    }

    private long items(long user) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM cashflow_item WHERE user_id = ?", Long.class, user);
    }

    private static long id(String response) {
        return ((Number) JsonPath.read(response, "$.data.cashflowItemId")).longValue();
    }
}
