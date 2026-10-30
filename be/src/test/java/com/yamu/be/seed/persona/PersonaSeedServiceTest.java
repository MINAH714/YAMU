package com.yamu.be.seed.persona;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.yamu.be.card.domain.CardIssuerStatus;
import com.yamu.be.global.crypto.CipherField;
import com.yamu.be.global.crypto.LookupHasher;
import com.yamu.be.global.crypto.PersonalDataCipher;
import com.yamu.be.global.finapi.FinApiClient;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.global.finapi.FinApiException;
import com.yamu.be.global.finapi.FinApiOutcome;
import com.yamu.be.seed.persona.PersonaSeedFile.Card;
import com.yamu.be.seed.persona.PersonaSeedFile.Persona;
import com.yamu.be.seed.persona.PersonaSeedService.Outcome;
import com.yamu.be.seed.persona.PersonaSeedService.Result;
import com.yamu.be.support.MySqlTestSupport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 가입 시연용 페르소나 시드(Story 4.11). 실제 MySQL에 넣고, SSAFY 계좌 목록은 가짜로 돌려준다.
 * 다른 테스트와 같은 DB를 쓰므로 전체를 지우지 않고, 테스트마다 번호·회원 ID를 새로 만든다.
 */
@TestPropertySource(properties = "spring.application.name=yamu-persona-seed-test")
class PersonaSeedServiceTest extends MySqlTestSupport {

    @Autowired PersonaSeedService seedService;
    @Autowired JdbcTemplate jdbc;
    @Autowired PersonalDataCipher cipher;
    @Autowired LookupHasher lookup;
    @Autowired ObjectMapper objectMapper;
    @MockitoBean FinApiClient finApiClient;

    private static final String CREDIT_PRODUCT = "페르소나 테스트 신용";
    private static final String CHECK_PRODUCT = "페르소나 테스트 체크";

    @BeforeEach
    void products() {
        for (Object[] p : List.of(new Object[] {"TESTA", CREDIT_PRODUCT, "CREDIT"},
                new Object[] {"TESTA", CHECK_PRODUCT, "CHECK"},
                new Object[] {"TESTA", "이름 겹침", "CREDIT"}, new Object[] {"TESTB", "이름 겹침", "CREDIT"})) {
            jdbc.update("""
                    INSERT IGNORE INTO card_product (issuer_code, product_name, card_type, created_at, updated_at)
                    VALUES (?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                    """, p);
        }
    }

    @Test
    void PENDING_사용자와_PENDING_카드를_넣고_앱_키로_풀린다() {
        Persona persona = persona("A 직장인", List.of(card(CREDIT_PRODUCT), card(CHECK_PRODUCT)));
        accounts(persona, 2);

        Outcome outcome = seedService.seed(List.of(persona), false).outcomes().get(0);

        assertThat(outcome.result()).isEqualTo(Result.CREATED);
        Map<String, Object> user = userRow(persona);
        assertThat(user.get("status")).isEqualTo("PENDING");
        assertThat(user.get("name")).isEqualTo(persona.name());
        assertThat(user.get("pin_hash")).isNull();
        assertThat(user.get("identity_verified_at")).isNull();
        assertThat(cipher.decrypt((byte[]) user.get("phone_enc"), CipherField.APP_USER_PHONE)).isEqualTo(persona.phone());
        assertThat(cipher.decrypt((byte[]) user.get("ssafy_user_key"), CipherField.APP_USER_SSAFY_USER_KEY))
                .isEqualTo(persona.ssafyUserKey());
        long userId = ((Number) user.get("user_id")).longValue();
        assertThat(count("linked_account", userId)).isZero();
        assertThat(count("user_device", userId)).isZero();
        List<Map<String, Object>> cards = jdbc.queryForList(
                "SELECT * FROM user_card WHERE user_id = ? ORDER BY user_card_id", userId);
        assertThat(cards).hasSize(2).allSatisfy(c -> {
            assertThat(c.get("status")).isEqualTo("PENDING");
            assertThat(c.get("linked_account_id")).isNull();
            assertThat(c.get("registered_at")).isNull();
        });
        String cardNo = persona.cards().get(0).cardNo();
        assertThat(cipher.decrypt((byte[]) cards.get(0).get("card_no_enc"), CipherField.USER_CARD_NO)).isEqualTo(cardNo);
        assertThat((byte[]) cards.get(0).get("card_no_hash")).isEqualTo(lookup.card(cardNo));
        assertThat(cards.get(0).get("card_last4")).isEqualTo(cardNo.substring(12));
    }

    @Test
    void 다시_돌리면_건너뛰고_중복이_생기지_않는다() {
        Persona persona = persona("B", List.of(card(CREDIT_PRODUCT)));
        accounts(persona, 1);
        seedService.seed(List.of(persona), false);

        Outcome again = seedService.seed(List.of(persona), false).outcomes().get(0);

        assertThat(again.result()).isEqualTo(Result.SKIPPED_EXISTS);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE phone_hash = ?", Integer.class,
                lookup.phone(persona.phone()))).isEqualTo(1);
    }

    @Test
    void 초기화는_PENDING만_다시_넣고_가입을_마친_사람은_그대로_둔다() {
        Persona pending = persona("PENDING", List.of(card(CREDIT_PRODUCT)));
        Persona active = persona("ACTIVE", List.of(card(CREDIT_PRODUCT)));
        accounts(pending, 1);
        accounts(active, 1);
        seedService.seed(List.of(pending, active), false);
        long pendingId = userId(pending);
        long activeId = userId(active);
        // 가입 중 연결한 계좌가 있어도 초기화로 같이 지운다.
        jdbc.update("""
                INSERT INTO linked_account (user_id, bank_code, account_no_enc, account_no_hash, account_last4, status,
                    connected_at, created_at, updated_at)
                VALUES (?, '088', ?, ?, '0001', 'CONNECTED', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, pendingId, cipher.encrypt("0880000000000001", CipherField.LINKED_ACCOUNT_NO),
                lookup.account("088", "0880000000000001"));
        jdbc.update("""
                UPDATE app_user SET status = 'ACTIVE', pin_hash = 'x', pin_updated_at = UTC_TIMESTAMP(6),
                    identity_verified_at = UTC_TIMESTAMP(6) WHERE user_id = ?
                """, activeId);

        List<Outcome> outcomes = seedService.seed(List.of(pending, active), true).outcomes();

        assertThat(outcomes).extracting(Outcome::result).containsExactly(Result.RESET, Result.SKIPPED_NOT_PENDING);
        assertThat(userId(pending)).isNotEqualTo(pendingId);
        assertThat(count("linked_account", userId(pending))).isZero();
        assertThat(count("user_card", userId(pending))).isEqualTo(1);
        assertThat(userId(active)).isEqualTo(activeId);
        assertThat(jdbc.queryForObject("SELECT status FROM app_user WHERE user_id = ?", String.class, activeId))
                .isEqualTo("ACTIVE");
    }

    @Test
    void 한_명이_실패해도_다른_사람은_들어간다() {
        Persona noProduct = persona("상품 없음", List.of(card("없는 상품")));
        Persona ambiguous = persona("이름 겹침", List.of(card("이름 겹침")));
        Persona ok = persona("정상", List.of(card(CREDIT_PRODUCT)));
        accounts(ok, 1);

        List<Outcome> outcomes = seedService.seed(List.of(noProduct, ambiguous, ok), false).outcomes();

        assertThat(outcomes).extracting(Outcome::result).containsExactly(Result.FAILED, Result.FAILED, Result.CREATED);
        assertThat(outcomes.get(0).message()).contains("카드 상품을 찾을 수 없습니다");
        assertThat(outcomes.get(1).message()).contains("issuerCode");
        assertThat(exists(noProduct)).isFalse();
        assertThat(exists(ok)).isTrue();
        // 상품이 없으면 금융망도 부르지 않는다.
        verify(finApiClient, never()).post(eq(PersonaAccountCheck.LIST_PATH), eq(noProduct.ssafyUserKey()), isNull(),
                eq(JsonNode.class));
    }

    @Test
    void 이름이_겹치는_상품은_카드사를_넣으면_찾는다() {
        Persona persona = persona("카드사 지정", List.of(new Card("이름 겹침", "TESTB", null, cardNo(), null, null)));
        accounts(persona, 1);

        assertThat(seedService.seed(List.of(persona), false).outcomes().get(0).result()).isEqualTo(Result.CREATED);
    }

    @Test
    void SSAFY_계좌가_없거나_조회가_실패하면_넣지_않는다() {
        Persona noAccount = persona("계좌 0개", List.of());
        Persona brokenKey = persona("키 오류", List.of());
        accounts(noAccount, 0);
        FinApiException rejected = rejected();
        when(finApiClient.post(eq(PersonaAccountCheck.LIST_PATH), eq(brokenKey.ssafyUserKey()), isNull(),
                eq(JsonNode.class))).thenThrow(rejected);

        List<Outcome> outcomes = seedService.seed(List.of(noAccount, brokenKey), false).outcomes();

        assertThat(outcomes).extracting(Outcome::result).containsExactly(Result.FAILED, Result.FAILED);
        assertThat(outcomes.get(0).message()).contains("계좌가 없습니다");
        assertThat(outcomes.get(1).message()).contains("SSAFY 계좌 목록 조회 실패").doesNotContain(brokenKey.ssafyUserKey());
        assertThat(exists(noAccount)).isFalse();
        assertThat(exists(brokenKey)).isFalse();
    }

    @Test
    void 잘못된_입력은_그_사람만_실패하고_메시지에_개인정보가_없다() {
        Persona badPhone = new Persona("번호 오류", "김야무", LocalDate.of(1995, 1, 1), "02123456789",
                memberId(), "key-" + cardNo(), List.of());
        Persona badCard = new Persona("카드 오류", "김야무", LocalDate.of(1995, 1, 1), phone(), memberId(),
                "key-" + cardNo(), List.of(new Card(CREDIT_PRODUCT, null, null, "1234", null, null)));
        Persona blankName = new Persona(null, "  ", LocalDate.of(1995, 1, 1), phone(), memberId(),
                "key-" + cardNo(), List.of());

        List<Outcome> outcomes = seedService.seed(List.of(badPhone, badCard, blankName), false).outcomes();

        assertThat(outcomes).extracting(Outcome::result).containsOnly(Result.FAILED);
        assertThat(outcomes.get(0).message()).doesNotContain("02123456789");
        assertThat(outcomes.get(1).message()).contains("cardNo").doesNotContain("1234");
        assertThat(outcomes.get(2).persona()).isEqualTo("#3");
        assertThat(outcomes.get(2).message()).contains("name");
    }

    @Test
    void 같은_파일에_같은_사람이_두_번_있으면_뒤의_것은_실패한다() {
        Persona first = persona("첫째", List.of());
        Persona copy = new Persona("둘째", "다른이름", first.birthDate(), first.phone(), memberId(), "key-x", List.of());
        accounts(first, 1);

        List<Outcome> outcomes = seedService.seed(List.of(first, copy), false).outcomes();

        assertThat(outcomes).extracting(Outcome::result).containsExactly(Result.CREATED, Result.FAILED);
        assertThat(outcomes.get(1).message()).contains("같은 사람");
    }

    @Test
    void 두_실행이_동시에_같은_사람을_넣어도_한_명만_생긴다() throws Exception {
        Persona persona = persona("동시", List.of(card(CREDIT_PRODUCT)));
        accounts(persona, 1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Outcome>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return seedService.seed(List.of(persona), false).outcomes().get(0);
                }));
            }
            start.countDown();
            List<Result> results = new ArrayList<>();
            for (Future<Outcome> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS).result());
            }
            assertThat(results).containsExactlyInAnyOrder(Result.CREATED, Result.SKIPPED_EXISTS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE phone_hash = ?", Integer.class,
                lookup.phone(persona.phone()))).isEqualTo(1);
        assertThat(count("user_card", userId(persona))).isEqualTo(1);
    }

    @Test
    void 파일을_읽는다_형식이_틀리면_내용_없이_위치만_알린다(@TempDir Path dir) throws Exception {
        PersonaSeedRunner runner = new PersonaSeedRunner(seedService, objectMapper, null);
        Path good = dir.resolve("good.json");
        Files.writeString(good, """
                [{"label":"A","name":"김야무","birthDate":"1995-03-14","phone":"01000000011",
                  "ssafyMemberId":"a@yamu.app","ssafyUserKey":"k",
                  "cards":[{"productName":"P","cardNo":"9410000000000011","expiry":"2029-08"}]}]
                """);
        Path bad = dir.resolve("bad.json");
        Files.writeString(bad, "[{\"name\":\"김야무\", \"phone\": 01099998888}]");

        List<Persona> personas = runner.read(good);
        assertThat(personas).singleElement().satisfies(p -> {
            assertThat(p.cards().get(0).expiry()).isEqualTo(YearMonth.of(2029, 8));
            assertThat(p.cards().get(0).issuerStatus()).isEqualTo(CardIssuerStatus.NORMAL);
        });
        assertThatThrownBy(() -> runner.read(bad)).hasMessageContaining("JSON 형식 오류")
                .hasMessageNotContaining("01099998888").hasMessageNotContaining("김야무");
        assertThatThrownBy(() -> runner.read(dir.resolve("none.json"))).hasMessageContaining("persona.example.json");
    }

    private static FinApiException rejected() {
        FinApiException rejected = mock(FinApiException.class);
        when(rejected.getErrorCode()).thenReturn(ErrorCode.FIN_API_ERROR);
        when(rejected.getOutcome()).thenReturn(FinApiOutcome.REJECTED);
        return rejected;
    }

    private Persona persona(String label, List<Card> cards) {
        return new Persona(label, "김야무", LocalDate.of(1995, 3, 14), phone(), memberId(), "key-" + cardNo(), cards);
    }

    private Card card(String productName) {
        return new Card(productName, null, null, cardNo(), YearMonth.of(2029, 8), null);
    }

    private void accounts(Persona persona, int count) {
        ArrayNode list = objectMapper.createArrayNode();
        for (int i = 0; i < count; i++) {
            list.addObject().put("bankCode", "088").put("accountNo", "08800000000000" + i);
        }
        when(finApiClient.post(eq(PersonaAccountCheck.LIST_PATH), eq(persona.ssafyUserKey()), isNull(),
                eq(JsonNode.class))).thenReturn(list);
    }

    private Map<String, Object> userRow(Persona persona) {
        return jdbc.queryForMap("SELECT * FROM app_user WHERE phone_hash = ? AND birth_date = ?",
                lookup.phone(persona.phone()), persona.birthDate());
    }

    private long userId(Persona persona) {
        return ((Number) userRow(persona).get("user_id")).longValue();
    }

    private boolean exists(Persona persona) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE phone_hash = ?", Integer.class,
                lookup.phone(persona.phone())) > 0;
    }

    private int count(String table, long userId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE user_id = ?", Integer.class, userId);
    }

    private static String phone() {
        return "010" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
    }

    private static String memberId() {
        return "p" + ThreadLocalRandom.current().nextLong(1_000_000_000L) + "@yamu.app";
    }

    private static String cardNo() {
        return "9410" + String.format("%012d", ThreadLocalRandom.current().nextLong(1_000_000_000_000L));
    }
}
