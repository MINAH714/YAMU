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
import com.yamu.be.account.domain.LinkedAccount;
import com.yamu.be.account.domain.LinkedAccountRepository;
import com.yamu.be.card.domain.CardIssuerStatus;
import com.yamu.be.card.domain.UserCard;
import com.yamu.be.card.domain.UserCardRepository;
import com.yamu.be.forecast.ForecastService;
import com.yamu.be.forecast.LivingCostForecast;
import com.yamu.be.global.crypto.CipherField;
import com.yamu.be.global.crypto.LookupHasher;
import com.yamu.be.global.crypto.PersonalDataCipher;
import com.yamu.be.global.finapi.FinApiClient;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.global.finapi.FinApiException;
import com.yamu.be.global.finapi.FinApiOutcome;
import com.yamu.be.seed.persona.PersonaSeedFile.Account;
import com.yamu.be.seed.persona.PersonaSeedFile.Card;
import com.yamu.be.seed.persona.PersonaSeedFile.Persona;
import com.yamu.be.seed.persona.PersonaSeedService.Outcome;
import com.yamu.be.seed.persona.PersonaSeedService.Result;
import com.yamu.be.support.MySqlTestSupport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
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
    @Autowired LinkedAccountRepository linkedAccountRepository;
    @Autowired UserCardRepository userCardRepository;
    @Autowired ForecastService forecasts;
    @MockitoBean FinApiClient finApiClient;

    /** SsafyAccountApi.LIST_PATH(패키지 전용)와 같은 경로. 계좌 목록을 가짜로 돌려줄 때 쓴다. */
    private static final String LIST_PATH = "/edu/demandDeposit/inquireDemandDepositAccountList";
    /** {@link #accounts}가 돌려주는 첫 계좌. 카드사가 정한 카드 계좌로 쓴다. */
    private static final Account ACCOUNT = new Account("088", "088-000-000000000");

    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
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
        // 과거 소비 시드가 쓰는 가맹점(카드 카탈로그 시드에 있는 브랜드).
        PersonaSpendingHistory.BRANDS.forEach((brand, category) -> jdbc.update("""
                INSERT IGNORE INTO merchant (merchant_name, brand_code, merchant_category_code, created_at, updated_at)
                VALUES (?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, brand, brand, category));
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
        // 카드사가 정한 계좌: 계좌 연결과 같은 해시, 끝 4자리. 신용카드만 결제일.
        assertThat(cards).allSatisfy(c -> {
            assertThat(c.get("issuer_account_bank_code")).isEqualTo("088");
            assertThat((byte[]) c.get("issuer_account_hash")).isEqualTo(lookup.account("088", "088000000000000"));
            assertThat(c.get("issuer_account_last4")).isEqualTo("0000");
        });
        assertThat(((Number) cards.get(0).get("payment_day")).intValue()).isEqualTo(14);
        assertThat(cards.get(1).get("payment_day")).isNull();
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
        verify(finApiClient, never()).post(eq(LIST_PATH), eq(noProduct.ssafyUserKey()), isNull(),
                eq(JsonNode.class));
    }

    @Test
    void 이름이_겹치는_상품은_카드사를_넣으면_찾는다() {
        Persona persona = persona("카드사 지정", List.of(new Card("이름 겹침", "TESTB", null, cardNo(), null, null, ACCOUNT, 14)));
        accounts(persona, 1);

        assertThat(seedService.seed(List.of(persona), false).outcomes().get(0).result()).isEqualTo(Result.CREATED);
    }

    @Test
    void SSAFY_계좌가_없거나_조회가_실패하면_넣지_않는다() {
        Persona noAccount = persona("계좌 0개", List.of());
        Persona brokenKey = persona("키 오류", List.of());
        accounts(noAccount, 0);
        FinApiException rejected = rejected();
        when(finApiClient.post(eq(LIST_PATH), eq(brokenKey.ssafyUserKey()), isNull(),
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
                "key-" + cardNo(), List.of(new Card(CREDIT_PRODUCT, null, null, "1234", null, null, ACCOUNT, 14)));
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
                  "cards":[{"productName":"P","cardNo":"9410000000000011","expiry":"2029-08",
                            "account":{"bankCode":"088","accountNo":"0880000000000"},"paymentDay":14}]}]
                """);
        Path bad = dir.resolve("bad.json");
        Files.writeString(bad, "[{\"name\":\"김야무\", \"phone\": 01099998888}]");

        List<Persona> personas = runner.read(good);
        assertThat(personas).singleElement().satisfies(p -> {
            assertThat(p.cards().get(0).expiry()).isEqualTo(YearMonth.of(2029, 8));
            assertThat(p.cards().get(0).issuerStatus()).isEqualTo(CardIssuerStatus.NORMAL);
            assertThat(p.cards().get(0).account()).isEqualTo(new Account("088", "0880000000000"));
            assertThat(p.cards().get(0).paymentDay()).isEqualTo(14);
        });
        assertThatThrownBy(() -> runner.read(bad)).hasMessageContaining("JSON 형식 오류")
                .hasMessageNotContaining("01099998888").hasMessageNotContaining("김야무");
        assertThatThrownBy(() -> runner.read(dir.resolve("none.json"))).hasMessageContaining("persona.example.json");
    }

    @Test
    void 카드_종류와_결제일이_맞지_않으면_그_사람만_실패한다() {
        Persona creditNoDay = persona("신용 결제일 없음", List.of(
                new Card(CREDIT_PRODUCT, null, null, cardNo(), null, null, ACCOUNT, null)));
        Persona checkWithDay = persona("체크 결제일 있음", List.of(
                new Card(CHECK_PRODUCT, null, null, cardNo(), null, null, ACCOUNT, 10)));
        Persona noAccount = persona("계좌 없음", List.of(
                new Card(CREDIT_PRODUCT, null, null, cardNo(), null, null, null, 14)));
        Persona dayOutOfRange = persona("결제일 범위", List.of(
                new Card(CREDIT_PRODUCT, null, null, cardNo(), null, null, ACCOUNT, 28)));
        List.of(creditNoDay, checkWithDay, noAccount, dayOutOfRange).forEach(p -> accounts(p, 1));

        List<Outcome> outcomes = seedService.seed(List.of(creditNoDay, checkWithDay, noAccount, dayOutOfRange), false)
                .outcomes();

        assertThat(outcomes).extracting(Outcome::result).containsOnly(Result.FAILED);
        assertThat(outcomes.get(0).message()).contains("신용카드").contains("paymentDay");
        assertThat(outcomes.get(1).message()).contains("체크카드").contains("paymentDay");
        assertThat(outcomes.get(2).message()).contains("account");
        assertThat(outcomes.get(3).message()).contains("paymentDay");
        assertThat(List.of(creditNoDay, checkWithDay, noAccount, dayOutOfRange)).noneMatch(this::exists);
    }

    @Test
    void 카드_계좌가_그_회원의_SSAFY_계좌_목록에_없으면_넣지_않고_계좌번호를_남기지_않는다() {
        Account elsewhere = new Account("004", "0049999999999912");
        Persona persona = persona("남의 계좌", List.of(
                new Card(CREDIT_PRODUCT, null, null, cardNo(), null, null, elsewhere, 14)));
        accounts(persona, 2);

        Outcome outcome = seedService.seed(List.of(persona), false).outcomes().get(0);

        assertThat(outcome.result()).isEqualTo(Result.FAILED);
        assertThat(outcome.message()).contains("계좌 목록에 없습니다").contains("004")
                .doesNotContain("0049999999999912").doesNotContain("9912");
        assertThat(exists(persona)).isFalse();
    }

    @Test
    void 시드한_카드_계좌는_가입_중_연결한_계좌와_카드_등록의_대조_방식으로_맞춰진다() {
        Persona persona = persona("대조", List.of(card(CREDIT_PRODUCT)));
        accounts(persona, 2);
        seedService.seed(List.of(persona), false);
        long userId = userId(persona);
        // 가입 중 계좌 연결이 저장하는 값과 같게 넣는다(AccountLinkService: lookupHasher.account(SSAFY 은행 코드, 계좌번호)).
        String ssafyAccountNo = "088000000000000";
        jdbc.update("""
                INSERT INTO linked_account (user_id, bank_code, account_no_enc, account_no_hash, account_last4, status,
                    connected_at, created_at, updated_at)
                VALUES (?, '088', ?, ?, '0000', 'CONNECTED', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, userId, cipher.encrypt(ssafyAccountNo, CipherField.LINKED_ACCOUNT_NO),
                lookup.account("088", ssafyAccountNo));

        UserCard card = userCardRepository.findByUserIdAndCardNoHash(userId,
                lookup.card(persona.cards().get(0).cardNo())).orElseThrow();
        // 카드 확인·등록(CardRegistrationService.connectedIssuerAccount)과 같은 조회로 찾는다.
        LinkedAccount matched = linkedAccountRepository.findByUserIdAndBankCodeAndAccountNoHash(userId,
                card.getIssuerAccountBankCode(), card.getIssuerAccountHash()).orElseThrow();

        assertThat(card.hasIssuerAccount()).isTrue();
        assertThat(card.getIssuerAccountLast4()).isEqualTo("0000");
        assertThat(card.getPaymentDay()).isEqualTo(14);
        assertThat(matched.getUserId()).isEqualTo(userId);
        assertThat(matched.isConnected()).isTrue();
    }

    @Test
    void 카드_등록_전_6개월_모의_소비와_달별_실적을_넣는다() {
        Persona persona = persona("과거 소비", List.of(card(CHECK_PRODUCT), card(CREDIT_PRODUCT)));
        accounts(persona, 1);

        seedService.seed(List.of(persona), false);

        long userId = userId(persona);
        LocalDate today = LocalDate.now(KOREA);
        LocalDate from = YearMonth.from(today).minusMonths(PersonaSpendingHistory.MONTHS).atDay(1);
        List<Map<String, Object>> payments = jdbc.queryForList("""
                SELECT p.*, m.brand_code, cp.card_type FROM payment_transaction p
                  JOIN merchant m ON m.merchant_id = p.merchant_id
                  JOIN card_product cp ON cp.card_product_id = p.card_product_id
                 WHERE p.user_id = ?
                """, userId);
        assertThat(payments).isNotEmpty().allSatisfy(p -> {
            assertThat(p.get("source")).isEqualTo("SEED");
            assertThat(p.get("status")).isEqualTo("APPROVED");
            assertThat(p.get("spending_class")).isNull();
            assertThat(korean(p.get("approved_at"))).isBetween(from, today.minusDays(1));
            // 반복 지출 규칙에 일상 소비가 잘못 걸리지 않게 10원 단위로 흩뜨린다
            assertThat(amount(p) % 10).isZero();
        });
        List<Map<String, Object>> check = payments.stream().filter(p -> "CHECK".equals(p.get("card_type"))).toList();
        long netflixMonths = from.datesUntil(today).filter(day -> day.getDayOfMonth() == 5).count();
        assertThat(check.stream().filter(p -> "NETFLIX".equals(p.get("brand_code"))).toList())
                .hasSize((int) netflixMonths).allSatisfy(p -> assertThat(amount(p)).isEqualTo(13_500L));
        assertThat(check.stream().filter(p -> amount(p) == 1_290_000L).toList()).hasSize(1);
        assertThat(payments.stream().filter(p -> ((Number) p.get("installment_months")).intValue() == 3).toList())
                .singleElement().satisfies(p -> assertThat(p.get("card_type")).isEqualTo("CREDIT"));
        // 다 찬 달의 체크카드 일상 소비(넷플릭스·큰 지출 제외)는 회원 ID와 관계없이 달마다 60만~70만 원 안
        for (int back = 1; back <= PersonaSpendingHistory.MONTHS; back++) {
            YearMonth month = YearMonth.from(today).minusMonths(back);
            long daily = check.stream()
                    .filter(p -> YearMonth.from(korean(p.get("approved_at"))).equals(month))
                    .filter(p -> !"NETFLIX".equals(p.get("brand_code")))
                    .mapToLong(PersonaSeedServiceTest::amount)
                    .filter(amount -> amount < 1_000_000L).sum();
            assertThat(daily).as(month.toString())
                    .isBetween(PersonaSpendingHistory.MONTHLY_MIN, PersonaSpendingHistory.MONTHLY_MAX);
        }
        // 실적은 카드마다 기간의 모든 달(이번 달 포함)에 넣은 결제의 단순 합
        List<Map<String, Object>> performance = jdbc.queryForList("""
                SELECT p.recognized_amount,
                       (SELECT COALESCE(SUM(t.approved_amount), 0) FROM payment_transaction t
                         WHERE t.user_card_id = p.user_card_id AND t.performance_month = p.performance_month) AS paid
                  FROM card_monthly_performance p JOIN user_card c ON c.user_card_id = p.user_card_id
                 WHERE c.user_id = ?
                """, userId);
        assertThat(performance).hasSize(2 * (PersonaSpendingHistory.MONTHS + 1))
                .allSatisfy(row -> assertThat(((Number) row.get("recognized_amount")).longValue())
                        .isEqualTo(((Number) row.get("paid")).longValue()));
    }

    @Test
    void 가입하고_카드를_등록하면_잔고예보_생활비가_SEED_HIGH로_나온다() {
        Persona persona = persona("생활비", List.of(card(CHECK_PRODUCT)));
        accounts(persona, 1);
        seedService.seed(List.of(persona), false);
        long userId = userId(persona);
        // 가입(계좌 연결·ACTIVE)과 카드 등록(계좌 연결·등록 시각)을 마친 상태로 바꾼다
        String accountNo = "088000000000000";
        jdbc.update("""
                INSERT INTO linked_account (user_id, bank_code, account_no_enc, account_no_hash, account_last4, status,
                    connected_at, created_at, updated_at)
                VALUES (?, '088', ?, ?, '0000', 'CONNECTED', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, userId, cipher.encrypt(accountNo, CipherField.LINKED_ACCOUNT_NO), lookup.account("088", accountNo));
        long accountId = jdbc.queryForObject("SELECT linked_account_id FROM linked_account WHERE user_id = ?",
                Long.class, userId);
        jdbc.update("UPDATE app_user SET status = 'ACTIVE', pin_hash = 'x', pin_updated_at = UTC_TIMESTAMP(6), "
                + "identity_verified_at = UTC_TIMESTAMP(6) WHERE user_id = ?", userId);
        jdbc.update("UPDATE user_card SET status = 'ACTIVE', linked_account_id = ?, registered_at = UTC_TIMESTAMP(6) "
                + "WHERE user_id = ?", accountId, userId);

        LivingCostForecast living = forecasts.livingCost(userId, List.of(accountId), false);

        assertThat(String.valueOf(living.dataOrigin())).isEqualTo("SEED");
        assertThat(String.valueOf(living.confidence())).isEqualTo("HIGH");
        // 큰 지출(129만 원)과 반복 지출(넷플릭스)은 빠져 달 평균이 일상 소비 수준
        assertThat(living.monthlyAverage()).isBetween(500_000L, 800_000L);
    }

    @Test
    void 초기화하면_과거_소비와_실적도_지우고_같은_값으로_다시_넣는다() {
        Persona persona = persona("초기화", List.of(card(CHECK_PRODUCT), card(CREDIT_PRODUCT)));
        accounts(persona, 1);
        seedService.seed(List.of(persona), false);
        long before = userId(persona);
        Map<String, Object> first = jdbc.queryForMap(
                "SELECT COUNT(*) AS n, SUM(approved_amount) AS total FROM payment_transaction WHERE user_id = ?", before);

        assertThat(seedService.seed(List.of(persona), true).outcomes().get(0).result()).isEqualTo(Result.RESET);

        long after = userId(persona);
        assertThat(count("payment_transaction", before)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM card_monthly_performance p JOIN user_card c ON c.user_card_id = p.user_card_id "
                        + "WHERE c.user_id = ?", Integer.class, before)).isZero();
        assertThat(jdbc.queryForMap(
                "SELECT COUNT(*) AS n, SUM(approved_amount) AS total FROM payment_transaction WHERE user_id = ?", after))
                .isEqualTo(first);
    }

    @Test
    void 초기화는_시드가_아닌_결제가_있으면_아무것도_지우지_않고_실패한다() {
        Persona persona = persona("다른 결제", List.of(card(CHECK_PRODUCT)));
        accounts(persona, 1);
        seedService.seed(List.of(persona), false);
        long userId = userId(persona);
        int seeded = count("payment_transaction", userId);
        // 다른 경로로 들어온 결제(YAMU) 한 건을 만든다
        jdbc.update("""
                INSERT INTO payment_transaction (user_id, user_card_id, card_product_id, merchant_id, payment_channel,
                    facility_type, merchant_context_snapshot, request_key, request_fingerprint, source, status,
                    requested_amount, approved_amount, installment_months, recognized_performance_amount,
                    performance_basis_amount, performance_month, requested_at, approved_at, created_at, updated_at)
                SELECT user_id, user_card_id, card_product_id, merchant_id, payment_channel, facility_type,
                       merchant_context_snapshot, UUID(), request_fingerprint, 'YAMU', status, requested_amount,
                       approved_amount, installment_months, recognized_performance_amount, performance_basis_amount,
                       performance_month, requested_at, approved_at, created_at, updated_at
                  FROM payment_transaction WHERE user_id = ? LIMIT 1
                """, userId);

        Outcome outcome = seedService.seed(List.of(persona), true).outcomes().get(0);

        assertThat(outcome.result()).isEqualTo(Result.FAILED);
        assertThat(userId(persona)).isEqualTo(userId);
        assertThat(count("payment_transaction", userId)).isEqualTo(seeded + 1);
    }

    @Test
    void 가입_전인데_모의_소비를_오늘_전에_넣었으면_건너뛰면서_reset을_안내한다() {
        Persona persona = persona("오래된 시드", List.of(card(CHECK_PRODUCT)));
        accounts(persona, 1);
        seedService.seed(List.of(persona), false);
        long userId = userId(persona);

        assertThat(seedService.seed(List.of(persona), false).outcomes().get(0).message()).doesNotContain("--reset");

        jdbc.update("UPDATE payment_transaction SET created_at = created_at - INTERVAL 2 DAY WHERE user_id = ?", userId);
        Outcome stale = seedService.seed(List.of(persona), false).outcomes().get(0);

        assertThat(stale.result()).isEqualTo(Result.SKIPPED_EXISTS);
        assertThat(stale.message()).contains("--reset");
    }

    private static long amount(Map<String, Object> payment) {
        return ((Number) payment.get("approved_amount")).longValue();
    }

    private static LocalDate korean(Object utc) {
        return ((java.sql.Timestamp) utc).toInstant().atZone(KOREA).toLocalDate();
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
        // 체크카드는 결제일이 없고, 신용카드(나머지 테스트 상품)는 14일.
        return new Card(productName, null, null, cardNo(), YearMonth.of(2029, 8), null, ACCOUNT,
                CHECK_PRODUCT.equals(productName) ? null : 14);
    }

    private void accounts(Persona persona, int count) {
        ArrayNode list = objectMapper.createArrayNode();
        for (int i = 0; i < count; i++) {
            list.addObject().put("bankCode", "088").put("accountNo", "08800000000000" + i);
        }
        when(finApiClient.post(eq(LIST_PATH), eq(persona.ssafyUserKey()), isNull(),
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
