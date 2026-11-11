package com.yamu.be.seed.persona;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.yamu.be.forecast.CardBillReader;
import com.yamu.be.global.crypto.CipherField;
import com.yamu.be.global.crypto.LookupHasher;
import com.yamu.be.global.crypto.PersonalDataCipher;
import com.yamu.be.global.finapi.FinApiClient;
import com.yamu.be.payment.billing.confirm.BillConfirmer;
import com.yamu.be.seed.persona.PersonaSeedFile.Account;
import com.yamu.be.seed.persona.PersonaSeedFile.Card;
import com.yamu.be.seed.persona.PersonaSeedFile.Persona;
import com.yamu.be.seed.persona.PersonaSeedService.Result;
import com.yamu.be.support.MySqlTestSupport;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 시연 페르소나 시드의 등록 전 신용카드 청구서(S15P31E103-371). 실제 MySQL에 넣고, SSAFY 계좌 목록은 가짜로 돌려준다.
 * 계산은 날짜를 고정해 SEED 결제를 직접 넣고 보고, 흐름은 실제 시드(오늘 날짜)로 가입·카드 등록·청구 확정·잔고예보까지 본다.
 */
@TestPropertySource(properties = "spring.application.name=yamu-persona-seed-bills-test")
class PersonaSeedBillsTest extends MySqlTestSupport {

    @Autowired PersonaSeedService seedService;
    @Autowired PersonaSeedBills seedBills;
    @Autowired JdbcTemplate jdbc;
    @Autowired PersonalDataCipher cipher;
    @Autowired LookupHasher lookup;
    @Autowired ObjectMapper objectMapper;
    @Autowired CardBillReader cardBills;
    @Autowired BillConfirmer confirmer;
    @MockitoBean FinApiClient finApiClient;

    private static final String LIST_PATH = "/edu/demandDeposit/inquireDemandDepositAccountList";
    private static final Account ACCOUNT = new Account("088", "088-000-000000000");
    private static final String ACCOUNT_NO = "088000000000000";
    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
    private static final String CREDIT_PRODUCT = "시드 청구 테스트 신용";
    private static final String CHECK_PRODUCT = "시드 청구 테스트 체크";

    @BeforeEach
    void products() {
        // 마감 일수 없음 = 기본 14일. 결제일 14일이면 M월 청구서는 (M−1)월 1일~말일 사용분
        for (Object[] p : List.of(new Object[] {CREDIT_PRODUCT, "CREDIT"}, new Object[] {CHECK_PRODUCT, "CHECK"})) {
            jdbc.update("""
                    INSERT IGNORE INTO card_product (issuer_code, product_name, card_type, created_at, updated_at)
                    VALUES ('TESTA', ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                    """, p);
        }
        PersonaSpendingHistory.BRANDS.forEach((brand, category) -> jdbc.update("""
                INSERT IGNORE INTO merchant (merchant_name, brand_code, merchant_category_code, created_at, updated_at)
                VALUES (?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, brand, brand, category));
    }

    @Test
    void 출금일이_오늘_이후인_회차만_승인과_같은_청구_주기와_할부_나누기로_만든다() {
        long userId = seededUserWithoutSeedPayments();
        long card = cardId(userId, "CREDIT");
        long october = payment(userId, card, LocalDate.of(2026, 10, 20), 50_000, 1);
        long november = payment(userId, card, LocalDate.of(2026, 11, 3), 30_000, 1);
        long installment = payment(userId, card, LocalDate.of(2026, 8, 20), 900_001, 3);
        // 10월 청구서(10/14 출금)는 YAMU 전에 이미 냈다고 본다
        payment(userId, card, LocalDate.of(2026, 9, 25), 55_000, 1);
        // 체크카드는 청구가 없다
        payment(userId, cardId(userId, "CHECK"), LocalDate.of(2026, 10, 21), 7_000, 1);

        int created = seedBills.insert(userId, LocalDate.of(2026, 11, 9));

        assertThat(created).isEqualTo(2);
        assertThat(jdbc.queryForList("""
                SELECT billing_month, period_start, period_end, withdrawal_date, status, linked_account_id,
                       confirmed_amount
                  FROM card_bill WHERE user_id = ? ORDER BY billing_month
                """, userId)).extracting(r -> r.get("billing_month").toString(), r -> r.get("period_start").toString(),
                        r -> r.get("period_end").toString(), r -> r.get("withdrawal_date").toString(),
                        r -> r.get("status"), r -> r.get("linked_account_id"), r -> r.get("confirmed_amount"))
                // 11/14는 토요일이라 출금 예정일은 다음 영업일 11/16
                .containsExactly(
                        tuple("2026-11-01", "2026-10-01", "2026-10-31", "2026-11-16", "ACCUMULATING", null, null),
                        tuple("2026-12-01", "2026-11-01", "2026-11-30", "2026-12-14", "ACCUMULATING", null, null));
        // 할부 90만 1원: 9·10월 회차(이미 냄)는 없고 나머지 1원을 더한 3회차가 11월 청구서에
        assertThat(jdbc.queryForList("""
                SELECT i.payment_transaction_id, i.installment_no, i.billing_month, i.principal_amount,
                       i.billed_amount, i.status, b.billing_month AS bill_month
                  FROM bill_item i JOIN card_bill b ON b.card_bill_id = i.card_bill_id
                 WHERE i.user_id = ? ORDER BY i.billing_month, i.payment_transaction_id
                """, userId)).extracting(r -> ((Number) r.get("payment_transaction_id")).longValue(),
                        r -> ((Number) r.get("installment_no")).intValue(), r -> r.get("billing_month").toString(),
                        r -> ((Number) r.get("principal_amount")).longValue(),
                        r -> ((Number) r.get("billed_amount")).longValue(), r -> r.get("status"),
                        r -> r.get("bill_month").toString())
                .containsExactly(tuple(october, 1, "2026-11-01", 50_000L, 50_000L, "PENDING", "2026-11-01"),
                        tuple(installment, 3, "2026-11-01", 300_001L, 300_001L, "PENDING", "2026-11-01"),
                        tuple(november, 1, "2026-12-01", 30_000L, 30_000L, "PENDING", "2026-12-01"));
    }

    @ParameterizedTest(name = "오늘 {0} → 11월 청구서 {1}")
    @CsvSource({"2026-11-16, true", "2026-11-17, false"})
    void 출금_예정일이_오늘이면_넣고_지났으면_넣지_않는다(LocalDate today, boolean included) {
        long userId = seededUserWithoutSeedPayments();
        payment(userId, cardId(userId, "CREDIT"), LocalDate.of(2026, 10, 20), 50_000, 1);

        seedBills.insert(userId, today);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM card_bill WHERE user_id = ?", Integer.class, userId))
                .isEqualTo(included ? 1 : 0);
    }

    @Test
    void 시드하면_진행_중_청구서가_생기고_카드_등록_뒤_확정되며_잔고예보에_보인다() {
        Persona persona = persona("청구 흐름", List.of(card(CHECK_PRODUCT), card(CREDIT_PRODUCT)));
        accounts(persona);
        LocalDate today = LocalDate.now(KOREA);

        assertThat(seedService.seed(List.of(persona), false).outcomes().get(0).result()).isEqualTo(Result.CREATED);

        long userId = userId(persona);
        List<Map<String, Object>> bills = jdbc.queryForList("""
                SELECT b.card_bill_id, b.status, b.linked_account_id, b.withdrawal_date,
                       (SELECT SUM(i.principal_amount) FROM bill_item i WHERE i.card_bill_id = b.card_bill_id) AS total
                  FROM card_bill b WHERE b.user_id = ? ORDER BY b.billing_month
                """, userId);
        // 등록 전 신용카드 사용분 중 아직 출금되지 않았을 청구서: 출금일이 오늘 이후이고 회차가 있다
        assertThat(bills).isNotEmpty().allSatisfy(b -> {
            assertThat(b.get("status")).isEqualTo("ACCUMULATING");
            assertThat(b.get("linked_account_id")).isNull();
            assertThat(LocalDate.parse(b.get("withdrawal_date").toString())).isAfterOrEqualTo(today);
            assertThat(((Number) b.get("total")).longValue()).isPositive();
        });
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM bill_item i
                  JOIN payment_transaction p ON p.payment_transaction_id = i.payment_transaction_id
                 WHERE i.user_id = ? AND (p.source <> 'SEED' OR i.status <> 'PENDING')
                """, Integer.class, userId)).isZero();
        long first = ((Number) bills.get(0).get("card_bill_id")).longValue();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        // 가입 전에는 출금 계좌가 없어 청구 확정이 건너뛴다
        assertThat(confirmer.confirm(first, now).kind()).isEqualTo(BillConfirmer.Kind.NO_ACCOUNT);

        long accountId = registerCards(userId);

        // 잔고예보는 진행 중 청구서를 카드의 지금 계좌로 읽는다
        assertThat(cardBills.outstanding(userId, List.of(accountId), today.plusYears(1)))
                .extracting(CardBillReader.OutstandingBill::accountId, CardBillReader.OutstandingBill::amount)
                .containsExactlyElementsOf(bills.stream()
                        .map(b -> tuple(accountId, ((Number) b.get("total")).longValue())).toList());
        // 카드 등록 뒤에는 그 계좌로 확정된다
        BillConfirmer.Result confirmed = confirmer.confirm(first, now);
        assertThat(confirmed.kind()).isEqualTo(BillConfirmer.Kind.CONFIRMED);
        assertThat(confirmed.confirmedAmount()).isEqualTo(((Number) bills.get(0).get("total")).longValue());
        assertThat(jdbc.queryForObject("SELECT linked_account_id FROM card_bill WHERE card_bill_id = ?", Long.class,
                first)).isEqualTo(accountId);
    }

    @Test
    void 초기화하면_시드_청구서도_지우고_같은_값으로_다시_넣는다() {
        Persona persona = persona("청구 초기화", List.of(card(CREDIT_PRODUCT)));
        accounts(persona);
        seedService.seed(List.of(persona), false);
        long before = userId(persona);
        List<Map<String, Object>> first = billSummary(before);
        assertThat(first).isNotEmpty();

        assertThat(seedService.seed(List.of(persona), true).outcomes().get(0).result()).isEqualTo(Result.RESET);

        long after = userId(persona);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM card_bill WHERE user_id = ?", Integer.class, before))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bill_item WHERE user_id = ?", Integer.class, before))
                .isZero();
        assertThat(billSummary(after)).isEqualTo(first);
    }

    @Test
    void 체크카드만_있으면_청구서를_만들지_않는다() {
        Persona persona = persona("체크만", List.of(card(CHECK_PRODUCT)));
        accounts(persona);

        seedService.seed(List.of(persona), false);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM card_bill WHERE user_id = ?", Integer.class,
                userId(persona))).isZero();
    }

    private List<Map<String, Object>> billSummary(long userId) {
        return jdbc.queryForList("""
                SELECT b.billing_month, b.withdrawal_date, b.status, COUNT(i.bill_item_id) AS items,
                       SUM(i.principal_amount) AS total
                  FROM card_bill b JOIN bill_item i ON i.card_bill_id = b.card_bill_id
                 WHERE b.user_id = ? GROUP BY b.card_bill_id, b.billing_month, b.withdrawal_date, b.status
                 ORDER BY b.billing_month
                """, userId);
    }

    /** 신용·체크카드 하나씩 있는 페르소나를 넣고, 날짜를 고정해 검사하도록 시드 청구서와 SEED 결제를 지운다. */
    private long seededUserWithoutSeedPayments() {
        Persona persona = persona("고정 날짜", List.of(card(CREDIT_PRODUCT), card(CHECK_PRODUCT)));
        accounts(persona);
        seedService.seed(List.of(persona), false);
        long userId = userId(persona);
        jdbc.update("DELETE FROM bill_item WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM card_bill WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM payment_transaction WHERE user_id = ?", userId);
        return userId;
    }

    private long cardId(long userId, String type) {
        return jdbc.queryForObject("""
                SELECT c.user_card_id FROM user_card c JOIN card_product p ON p.card_product_id = c.card_product_id
                 WHERE c.user_id = ? AND p.card_type = ?
                """, Long.class, userId, type);
    }

    /** 그날 한국 12시에 승인된 SEED 결제 한 건. */
    private long payment(long userId, long cardId, LocalDate day, long amount, int months) {
        String key = UUID.randomUUID().toString();
        Timestamp approved = Timestamp.from(day.atTime(12, 0).atZone(KOREA).toInstant());
        jdbc.update("""
                INSERT INTO payment_transaction (user_id, user_card_id, card_product_id, merchant_id, payment_channel,
                    facility_type, merchant_context_snapshot, request_key, request_fingerprint, source, status,
                    requested_amount, approved_amount, installment_months, recognized_performance_amount,
                    performance_basis_amount, performance_month, requested_at, approved_at, created_at, updated_at)
                SELECT c.user_id, c.user_card_id, c.card_product_id,
                       (SELECT merchant_id FROM merchant WHERE brand_code = 'COUPANG' LIMIT 1), 'ONLINE', 'GENERAL',
                       '{"brandCode":"COUPANG","source":"PERSONA_SEED"}', ?, UNHEX(SHA2(?, 256)),
                       'SEED', 'APPROVED', ?, ?, ?, ?, NULL,
                       ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
                  FROM user_card c WHERE c.user_card_id = ? AND c.user_id = ?
                """, key, key, amount, amount, months, amount,
                YearMonth.from(day).atDay(1), approved, approved, cardId, userId);
        return jdbc.queryForObject("SELECT payment_transaction_id FROM payment_transaction WHERE request_key = ?",
                Long.class, key);
    }

    /** 가입(계좌 연결·ACTIVE)과 카드 등록(계좌 연결·등록 시각)을 마친 상태로 바꾸고 계좌 번호를 돌려준다. */
    private long registerCards(long userId) {
        jdbc.update("""
                INSERT INTO linked_account (user_id, bank_code, account_no_enc, account_no_hash, account_last4, status,
                    connected_at, created_at, updated_at)
                VALUES (?, '088', ?, ?, '0000', 'CONNECTED', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, userId, cipher.encrypt(ACCOUNT_NO, CipherField.LINKED_ACCOUNT_NO),
                lookup.account("088", ACCOUNT_NO));
        long accountId = jdbc.queryForObject("SELECT linked_account_id FROM linked_account WHERE user_id = ?",
                Long.class, userId);
        jdbc.update("UPDATE app_user SET status = 'ACTIVE', pin_hash = 'x', pin_updated_at = UTC_TIMESTAMP(6), "
                + "identity_verified_at = UTC_TIMESTAMP(6) WHERE user_id = ?", userId);
        jdbc.update("UPDATE user_card SET status = 'ACTIVE', linked_account_id = ?, registered_at = UTC_TIMESTAMP(6) "
                + "WHERE user_id = ?", accountId, userId);
        return accountId;
    }

    private Persona persona(String label, List<Card> cards) {
        return new Persona(label, "김야무", LocalDate.of(1995, 3, 14), phone(), memberId(), "key-" + cardNo(), cards);
    }

    private Card card(String productName) {
        return new Card(productName, null, null, cardNo(), YearMonth.of(2029, 8), null, ACCOUNT,
                CHECK_PRODUCT.equals(productName) ? null : 14);
    }

    private void accounts(Persona persona) {
        ArrayNode list = objectMapper.createArrayNode();
        list.addObject().put("bankCode", "088").put("accountNo", ACCOUNT_NO);
        when(finApiClient.post(eq(LIST_PATH), eq(persona.ssafyUserKey()), isNull(), eq(JsonNode.class)))
                .thenReturn(list);
    }

    private long userId(Persona persona) {
        return jdbc.queryForObject("SELECT user_id FROM app_user WHERE phone_hash = ? AND birth_date = ?", Long.class,
                lookup.phone(persona.phone()), persona.birthDate());
    }

    private static String phone() {
        return "010" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
    }

    private static String memberId() {
        return "b" + ThreadLocalRandom.current().nextLong(1_000_000_000L) + "@yamu.app";
    }

    private static String cardNo() {
        return "9420" + String.format("%012d", ThreadLocalRandom.current().nextLong(1_000_000_000_000L));
    }
}
