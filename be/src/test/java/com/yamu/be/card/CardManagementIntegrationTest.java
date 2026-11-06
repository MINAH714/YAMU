package com.yamu.be.card;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.yamu.be.auth.StepUpTokenStore;
import com.yamu.be.auth.StepUpTokenStore.StepUpGrant;
import com.yamu.be.auth.domain.AuthMethod;
import com.yamu.be.auth.domain.StepUpPurpose;
import com.yamu.be.global.crypto.CipherField;
import com.yamu.be.global.crypto.LookupHasher;
import com.yamu.be.global.crypto.PersonalDataCipher;
import com.yamu.be.payment.billing.BillFixtures;
import com.yamu.be.payment.billing.BillingCycle;
import com.yamu.be.support.AuthUsers;
import com.yamu.be.support.Concurrently;
import com.yamu.be.support.MySqlTestSupport;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;

/**
 * 보유 카드 목록·상세·수정·순서 저장·등록 해제를 실제 MySQL·Redis로 확인한다.
 * 등록된 카드는 카드 등록을 마친 상태처럼 user_card ACTIVE 행으로 바로 넣는다.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {"spring.application.name=yamu-card-manage-integration-test",
        "yamu.card-image.base-url=https://images.example.com/"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CardManagementIntegrationTest extends MySqlTestSupport {

    @Container
    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired PersonalDataCipher cipher;
    @Autowired LookupHasher lookupHasher;
    @Autowired StepUpTokenStore stepUpTokenStore;

    long checkProduct;
    long creditProduct;
    Member member;
    long account;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM bill_item");
        jdbc.update("DELETE FROM payment_transaction");
        jdbc.update("DELETE FROM card_bill");
        jdbc.update("DELETE FROM card_monthly_performance");
        jdbc.update("DELETE FROM user_card");
        jdbc.update("DELETE FROM card_product");
        jdbc.update("DELETE FROM linked_account");
        jdbc.update("DELETE FROM step_up_audit_log");
        jdbc.update("DELETE FROM user_device_event");
        jdbc.update("DELETE FROM user_device");
        jdbc.update("DELETE FROM app_user");
        redis.getRequiredConnectionFactory().getConnection().serverCommands().flushAll();
        checkProduct = product("SHINHAN", "신한 카페 체크", "CHECK", null);
        creditProduct = product("HANA", "하나 생활 신용", "CREDIT", 13);
        member = member("01012345678");
        account = account(member.userId(), "5934");
    }

    // ───────── 목록 ─────────

    @Test
    void 상품_이미지는_목록_상세_수정에_같은_URL로_나오고_이미지_없는_카드는_null이다() throws Exception {
        String key = "cards/shinhan/static-front/test.png";
        String url = "https://images.example.com/" + key;
        jdbc.update("UPDATE card_product SET image_key=? WHERE card_product_id=?", key, checkProduct);
        long check = card(member.userId(), checkProduct, "9410123456785678", "ACTIVE", null, account, 1);
        long credit = card(member.userId(), creditProduct, "4570000011111234", "ACTIVE", 14, account, 2);

        get(member, "/api/cards")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cards[0].imageUrl").value(url))
                .andExpect(jsonPath("$.data.cards[1].imageUrl").hasJsonPath())
                .andExpect(jsonPath("$.data.cards[1].imageUrl").value(org.hamcrest.Matchers.nullValue()));
        get(member, "/api/cards/" + check).andExpect(jsonPath("$.data.imageUrl").value(url));
        patch(member, check, "{\"nickname\":\"새 별칭\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.imageUrl").value(url));
        get(member, "/api/cards/" + credit)
                .andExpect(jsonPath("$.data.imageUrl").hasJsonPath())
                .andExpect(jsonPath("$.data.imageUrl").value(org.hamcrest.Matchers.nullValue()));
        jdbc.update("UPDATE card_product SET image_key='cards/../invalid.png' WHERE card_product_id=?", checkProduct);
        get(member, "/api/cards/" + check).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.imageUrl").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void 목록은_등록된_카드만_등록_순으로_보이고_신용카드에만_청구_요약이_붙는다() throws Exception {
        long check = card(member.userId(), checkProduct, "9410123456785678", "ACTIVE", null, account, 1);
        long credit = card(member.userId(), creditProduct, "4570000011111234", "INACTIVE", 14, account, 2);
        card(member.userId(), checkProduct, "9410000022223333", "PENDING", null, null, 0);
        card(member.userId(), checkProduct, "9410000044445555", "UNREGISTERED", null, account, 3);
        performance(check, YearMonth.now(ZoneId.of("Asia/Seoul")), 320000);
        bill(credit, "UNPAID", LocalDate.now().minusDays(3));

        get(member, "/api/cards")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cards.length()").value(2))
                .andExpect(jsonPath("$.data.cards[0].userCardId").value(check))
                .andExpect(jsonPath("$.data.cards[0].nickname").value("신한 카페 체크"))
                .andExpect(jsonPath("$.data.cards[0].issuer.code").value("SHINHAN"))
                .andExpect(jsonPath("$.data.cards[0].issuer.name").value("신한카드"))
                .andExpect(jsonPath("$.data.cards[0].maskedNumber").value("9410-****-****-5678"))
                .andExpect(jsonPath("$.data.cards[0].cardType").value("CHECK"))
                .andExpect(jsonPath("$.data.cards[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.cards[0].issuerStatus").value("NORMAL"))
                .andExpect(jsonPath("$.data.cards[0].isDefault").value(false))
                .andExpect(jsonPath("$.data.cards[0].mainBenefits.length()").value(0))
                .andExpect(jsonPath("$.data.cards[0].performance.recognizedAmount").value(320000))
                .andExpect(jsonPath("$.data.cards[0].billingSummary").doesNotExist())
                .andExpect(jsonPath("$.data.cards[1].userCardId").value(credit))
                .andExpect(jsonPath("$.data.cards[1].maskedNumber").value("4570-****-****-1234"))
                .andExpect(jsonPath("$.data.cards[1].performance").doesNotExist())
                .andExpect(jsonPath("$.data.cards[1].billingSummary.paymentDay").value(14))
                .andExpect(jsonPath("$.data.cards[1].billingSummary.hasUnpaidBill").value(true));

        get(member, "/api/cards?status=INACTIVE")
                .andExpect(jsonPath("$.data.cards.length()").value(1))
                .andExpect(jsonPath("$.data.cards[0].userCardId").value(credit));
        get(member, "/api/cards?status=UNREGISTERED").andExpect(errorCode(400, "INVALID_REQUEST"));
    }

    @Test
    void 남의_카드는_목록에_없고_상세는_404다() throws Exception {
        Member other = member("01077778888");
        long others = card(other.userId(), checkProduct, "9410123456785678", "ACTIVE", null, null, 1);
        long pending = card(member.userId(), checkProduct, "9410000022223333", "PENDING", null, null, 0);

        get(member, "/api/cards").andExpect(jsonPath("$.data.cards.length()").value(0));
        get(member, "/api/cards/" + others).andExpect(errorCode(404, "RESOURCE_NOT_FOUND"));
        get(member, "/api/cards/" + pending).andExpect(errorCode(404, "RESOURCE_NOT_FOUND"));
        patch(member, others, "{\"nickname\":\"내 카드\"}").andExpect(errorCode(404, "RESOURCE_NOT_FOUND"));
    }

    // ───────── 상세 ─────────

    @Test
    void 체크카드_상세는_연결_계좌를_신용카드_상세는_결제_정보와_가장_가까운_확정_청구를_준다() throws Exception {
        long check = card(member.userId(), checkProduct, "9410123456785678", "ACTIVE", null, account, 1);
        long credit = card(member.userId(), creditProduct, "4570000011111234", "ACTIVE", 14, account, 2);
        long later = bill(credit, "CONFIRMED", LocalDate.of(2026, 12, 14));
        long sooner = bill(credit, "CONFIRMED", LocalDate.of(2026, 11, 16));

        get(member, "/api/cards/" + check)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.billing").doesNotExist())
                .andExpect(jsonPath("$.data.linkedAccount.accountId").value(account))
                .andExpect(jsonPath("$.data.linkedAccount.bankCode").value("088"))
                .andExpect(jsonPath("$.data.linkedAccount.bankName").value("신한은행"))
                .andExpect(jsonPath("$.data.linkedAccount.maskedAccountNo").value("****-****-****-5934"))
                .andExpect(jsonPath("$.data.issuer.closingDayOffset").doesNotExist());

        get(member, "/api/cards/" + credit)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.linkedAccount").doesNotExist())
                .andExpect(jsonPath("$.data.issuer.code").value("HANA"))
                .andExpect(jsonPath("$.data.issuer.closingDayOffset").value(13))
                .andExpect(jsonPath("$.data.billing.paymentDay").value(14))
                .andExpect(jsonPath("$.data.billing.paymentDayChangeableFrom").doesNotExist())
                .andExpect(jsonPath("$.data.billing.settlementAccountId").value(account))
                .andExpect(jsonPath("$.data.billing.nextWithdrawal.billId").value(sooner))
                .andExpect(jsonPath("$.data.billing.nextWithdrawal.amount").value(128500))
                .andExpect(jsonPath("$.data.billing.nextWithdrawal.withdrawalDate").value("2026-11-16"))
                .andExpect(jsonPath("$.data.billing.hasUnpaidBill").value(false))
                .andExpect(jsonPath("$.data.registeredAt").exists());
        assertThat(later).isNotEqualTo(sooner);
    }

    @Test
    void 진행_중_이용기간의_누적액은_오늘이_들어가는_청구서의_청구_전_회차_합계다() throws Exception {
        long credit = card(member.userId(), creditProduct, "4570000011111234", "ACTIVE", 14, account, 1);
        BillFixtures bills = new BillFixtures(jdbc);
        long merchant = bills.merchant("이마트");
        BillingCycle.Cycle now = BillingCycle.of(14, 13).cycleFor(todayKst());
        BillingCycle.Cycle next = BillingCycle.of(14, 13).cycleOf(now.billingMonth().plusMonths(1));
        BillingCycle.Cycle previous = BillingCycle.of(14, 13).cycleOf(now.billingMonth().minusMonths(1));
        long current = accumulatingBill(credit, now);
        long future = accumulatingBill(credit, next);
        // 마감이 지났지만 확정 배치가 아직 돌지 않은 지난 기간 청구서
        long overdue = accumulatingBill(credit, previous);
        LocalDateTime approvedAt = now.periodStart().atTime(3, 0);
        long lumpSum = bills.payment(member.userId(), credit, merchant, 30_000L, 1, approvedAt);
        bills.item(lumpSum, member.userId(), credit, current, 1, now.billingMonth(), 30_000L, "PENDING");
        long installment = bills.payment(member.userId(), credit, merchant, 90_000L, 3, approvedAt);
        bills.item(installment, member.userId(), credit, current, 1, now.billingMonth(), 30_000L, "PENDING");
        bills.item(installment, member.userId(), credit, future, 2, next.billingMonth(), 30_000L, "PENDING");
        long canceled = bills.payment(member.userId(), credit, merchant, 5_000L, 1, approvedAt);
        bills.item(canceled, member.userId(), credit, current, 1, now.billingMonth(), 5_000L, "EXCLUDED");
        long last = bills.payment(member.userId(), credit, merchant, 7_000L, 1, previous.periodStart().atTime(3, 0));
        bills.item(last, member.userId(), credit, overdue, 1, previous.billingMonth(), 7_000L, "PENDING");

        get(member, "/api/cards/" + credit)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.billing.currentCycle.usagePeriod.start").value(now.periodStart().toString()))
                .andExpect(jsonPath("$.data.billing.currentCycle.usagePeriod.end").value(now.periodEnd().toString()))
                .andExpect(jsonPath("$.data.billing.currentCycle.accumulatingAmount").value(60_000))
                .andExpect(jsonPath("$.data.billing.currentCycle.confirmAt")
                        .value(now.periodEnd().plusDays(1).toString()));
        get(member, "/api/cards")
                .andExpect(jsonPath("$.data.cards[0].billingSummary.accumulatingAmount").value(60_000));
        patch(member, credit, "{\"nickname\":\"생활비 카드\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.billing.currentCycle.accumulatingAmount").value(60_000));
    }

    @Test
    void 이번_기간에_쓴_돈이_없으면_0원이고_기간은_지금_결제일로_계산한다() throws Exception {
        long credit = card(member.userId(), creditProduct, "4570000011111234", "ACTIVE", 14, account, 1);
        long check = card(member.userId(), checkProduct, "9410123456785678", "ACTIVE", null, account, 2);
        BillingCycle.Cycle now = BillingCycle.of(14, 13).cycleFor(todayKst());

        get(member, "/api/cards/" + credit)
                .andExpect(jsonPath("$.data.billing.currentCycle.usagePeriod.start").value(now.periodStart().toString()))
                .andExpect(jsonPath("$.data.billing.currentCycle.usagePeriod.end").value(now.periodEnd().toString()))
                .andExpect(jsonPath("$.data.billing.currentCycle.accumulatingAmount").value(0))
                .andExpect(jsonPath("$.data.billing.currentCycle.confirmAt")
                        .value(now.periodEnd().plusDays(1).toString()));
        get(member, "/api/cards")
                .andExpect(jsonPath("$.data.cards[0].billingSummary.accumulatingAmount").value(0))
                .andExpect(jsonPath("$.data.cards[1].userCardId").value(check))
                .andExpect(jsonPath("$.data.cards[1].billingSummary").doesNotExist());
    }

    @Test
    void 결제일을_바꾼_뒤에도_이미_만든_이번_기간_청구서의_기간을_따른다() throws Exception {
        long credit = card(member.userId(), creditProduct, "4570000011111234", "ACTIVE", 14, account, 1);
        // 결제일 25일일 때 만든 청구서. 지금 결제일(14일)로 계산한 기간과 다르다
        BillingCycle.Cycle old = BillingCycle.of(25, 13).cycleFor(todayKst());
        long current = accumulatingBill(credit, old);
        BillFixtures bills = new BillFixtures(jdbc);
        long payment = bills.payment(member.userId(), credit, bills.merchant("이마트"), 12_000L, 1,
                old.periodStart().atTime(3, 0));
        bills.item(payment, member.userId(), credit, current, 1, old.billingMonth(), 12_000L, "PENDING");

        get(member, "/api/cards/" + credit)
                .andExpect(jsonPath("$.data.billing.currentCycle.usagePeriod.start").value(old.periodStart().toString()))
                .andExpect(jsonPath("$.data.billing.currentCycle.usagePeriod.end").value(old.periodEnd().toString()))
                .andExpect(jsonPath("$.data.billing.currentCycle.accumulatingAmount").value(12_000));
    }

    @Test
    void 남의_진행_중_청구서는_내_카드_누적액에_들어가지_않는다() throws Exception {
        long mine = card(member.userId(), creditProduct, "4570000011111234", "ACTIVE", 14, account, 1);
        Member other = member("01077778888");
        long otherAccount = account(other.userId(), "1111");
        long others = card(other.userId(), creditProduct, "4570000022225678", "ACTIVE", 14, otherAccount, 1);
        BillingCycle.Cycle now = BillingCycle.of(14, 13).cycleFor(todayKst());
        BillFixtures bills = new BillFixtures(jdbc);
        long othersBill = insert("card_bill", "card_bill_id", accumulatingRow(other.userId(), others, now));
        long payment = bills.payment(other.userId(), others, bills.merchant("이마트"), 50_000L, 1,
                now.periodStart().atTime(3, 0));
        bills.item(payment, other.userId(), others, othersBill, 1, now.billingMonth(), 50_000L, "PENDING");

        get(member, "/api/cards/" + mine).andExpect(jsonPath("$.data.billing.currentCycle.accumulatingAmount").value(0));
        get(other, "/api/cards/" + others)
                .andExpect(jsonPath("$.data.billing.currentCycle.accumulatingAmount").value(50_000));
    }

    @Test
    void 결제일이_청구_규칙이_받지_않는_값이면_진행_중_이용기간은_비운다() throws Exception {
        long credit = card(member.userId(), creditProduct, "4570000011111234", "ACTIVE", 14, account, 1);
        jdbc.update("UPDATE user_card SET payment_day = 28 WHERE user_card_id = ?", credit);

        get(member, "/api/cards/" + credit)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.billing.paymentDay").value(28))
                .andExpect(jsonPath("$.data.billing.currentCycle").doesNotExist());
        get(member, "/api/cards")
                .andExpect(jsonPath("$.data.cards[0].billingSummary.accumulatingAmount").doesNotExist());
    }

    // ───────── 수정 ─────────

    @Test
    void 별칭과_추천_포함_여부를_바꾸고_빈_별칭은_상품명으로_돌아간다() throws Exception {
        long check = card(member.userId(), checkProduct, "9410123456785678", "ACTIVE", null, account, 1);

        patch(member, check, "{\"nickname\":\"생활비 카드\",\"status\":\"INACTIVE\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nickname").value("생활비 카드"))
                .andExpect(jsonPath("$.data.status").value("INACTIVE"));
        patch(member, check, "{\"nickname\":\"\",\"status\":\"ACTIVE\"}")
                .andExpect(jsonPath("$.data.nickname").value("신한 카페 체크"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));

        patch(member, check, "{\"status\":\"UNREGISTERED\"}").andExpect(errorCode(400, "INVALID_REQUEST"));
        patch(member, check, "{\"nickname\":\"" + "가".repeat(21) + "\"}").andExpect(errorCode(400, "INVALID_REQUEST"));
    }

    @Test
    void 기본_카드는_사용자당_한_장이라_다른_카드를_지정하면_기존_지정이_풀린다() throws Exception {
        long first = card(member.userId(), checkProduct, "9410123456785678", "ACTIVE", null, account, 1);
        long second = card(member.userId(), creditProduct, "4570000011111234", "ACTIVE", 14, account, 2);

        patch(member, first, "{\"isDefault\":true}").andExpect(jsonPath("$.data.isDefault").value(true));
        patch(member, second, "{\"isDefault\":true}").andExpect(jsonPath("$.data.isDefault").value(true));
        assertThat(defaults()).containsExactly(second);

        patch(member, second, "{\"isDefault\":false}").andExpect(jsonPath("$.data.isDefault").value(false));
        assertThat(defaults()).isEmpty();
    }

    @Test
    void 결제일은_신용카드만_1일에서_27일이고_바꾼_뒤_3개월_안에는_다시_못_바꾼다() throws Exception {
        long check = card(member.userId(), checkProduct, "9410123456785678", "ACTIVE", null, account, 1);
        long credit = card(member.userId(), creditProduct, "4570000011111234", "ACTIVE", 14, account, 2);

        patch(member, check, "{\"paymentDay\":10}").andExpect(errorCode(400, "INVALID_PAYMENT_DAY"));
        patch(member, credit, "{\"paymentDay\":28}").andExpect(errorCode(400, "INVALID_PAYMENT_DAY"));
        // 지금과 같은 결제일은 변경이 아니라 제한 시계를 시작하지 않는다.
        patch(member, credit, "{\"paymentDay\":14}")
                .andExpect(jsonPath("$.data.billing.paymentDayChangeableFrom").doesNotExist());

        LocalDate changeableFrom = LocalDate.now(ZoneId.of("Asia/Seoul")).plusMonths(3);
        patch(member, credit, "{\"paymentDay\":20}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.billing.paymentDay").value(20))
                .andExpect(jsonPath("$.data.billing.paymentDayChangeableFrom").value(changeableFrom.toString()));
        patch(member, credit, "{\"paymentDay\":25,\"nickname\":\"바뀌면 안 됨\"}")
                .andExpect(errorCode(409, "PAYMENT_DAY_CHANGE_LOCKED"))
                .andExpect(jsonPath("$.error.details.changeableFrom").value(changeableFrom.toString()));
        // 거절된 요청의 다른 필드도 바뀌지 않는다.
        assertThat(jdbc.queryForMap("SELECT payment_day, card_alias FROM user_card WHERE user_card_id = ?", credit))
                .containsEntry("payment_day", 20).containsEntry("card_alias", null);
    }

    @Test
    void 결제계좌는_신용카드만_내_연결_계좌로_바꾼다() throws Exception {
        long check = card(member.userId(), checkProduct, "9410123456785678", "ACTIVE", null, account, 1);
        long credit = card(member.userId(), creditProduct, "4570000011111234", "ACTIVE", 14, account, 2);
        long second = account(member.userId(), "7777");
        long others = account(member("01077778888").userId(), "8888");

        patch(member, credit, "{\"settlementAccountId\":" + second + "}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.billing.settlementAccountId").value(second));
        patch(member, credit, "{\"settlementAccountId\":" + others + "}").andExpect(errorCode(404, "RESOURCE_NOT_FOUND"));
        patch(member, check, "{\"settlementAccountId\":" + second + "}").andExpect(errorCode(400, "INVALID_REQUEST"));
    }

    @Test
    void 해제한_카드는_수정할_수_없다() throws Exception {
        long removed = card(member.userId(), checkProduct, "9410123456785678", "UNREGISTERED", null, account, 1);

        patch(member, removed, "{\"nickname\":\"다시\"}").andExpect(errorCode(409, "CARD_UNREGISTERED"));
        get(member, "/api/cards/" + removed).andExpect(errorCode(404, "RESOURCE_NOT_FOUND"));
    }

    // ───────── 순서 ─────────

    @Test
    void 순서를_저장하면_목록이_그_순서로_나오고_빠지거나_겹치거나_남의_카드면_거절한다() throws Exception {
        long a = card(member.userId(), checkProduct, "9410123456785678", "ACTIVE", null, account, 1);
        long b = card(member.userId(), creditProduct, "4570000011111234", "INACTIVE", 14, account, 2);
        long c = card(member.userId(), checkProduct, "9410000022223333", "ACTIVE", null, account, 3);
        long others = card(member("01077778888").userId(), checkProduct, "9410000044445555", "ACTIVE", null, null, 1);

        put(member, "[" + c + "," + a + "," + b + "]")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fixedOrder[0]").value(c))
                .andExpect(jsonPath("$.data.fixedOrder[2]").value(b))
                .andExpect(jsonPath("$.data.updatedAt").exists());
        get(member, "/api/cards")
                .andExpect(jsonPath("$.data.cards[0].userCardId").value(c))
                .andExpect(jsonPath("$.data.cards[1].userCardId").value(a))
                .andExpect(jsonPath("$.data.cards[2].userCardId").value(b));

        put(member, "[" + c + "," + a + "]").andExpect(errorCode(400, "ORDER_INCOMPLETE"));
        put(member, "[" + c + "," + a + "," + a + "]").andExpect(errorCode(400, "ORDER_INCOMPLETE"));
        put(member, "[" + c + "," + a + "," + others + "]").andExpect(errorCode(400, "ORDER_INCOMPLETE"));
        // 거절된 순서는 저장되지 않는다.
        get(member, "/api/cards").andExpect(jsonPath("$.data.cards[0].userCardId").value(c));
    }

    // ───────── 등록 해제 ─────────

    @Test
    void 본인_인증_후_해제하면_목록에서_빠지고_기본_카드_지정도_풀린다() throws Exception {
        long card = card(member.userId(), checkProduct, "9410123456785678", "ACTIVE", null, account, 1);
        patch(member, card, "{\"isDefault\":true}").andExpect(status().isOk());

        delete(member, card, null).andExpect(errorCode(403, "STEP_UP_REQUIRED"));
        delete(member, card, stepUp(member, StepUpPurpose.CARD_REGISTER)).andExpect(errorCode(403, "STEP_UP_REQUIRED"));

        delete(member, card, stepUp(member, StepUpPurpose.CARD_DELETE)).andExpect(status().isNoContent());
        assertThat(jdbc.queryForMap("SELECT status, is_default FROM user_card WHERE user_card_id = ?", card))
                .containsEntry("status", "UNREGISTERED").containsEntry("is_default", false);
        get(member, "/api/cards").andExpect(jsonPath("$.data.cards.length()").value(0));
        // 이미 해제한 카드는 등록 카드가 아니라 404다.
        delete(member, card, stepUp(member, StepUpPurpose.CARD_DELETE)).andExpect(errorCode(404, "RESOURCE_NOT_FOUND"));
    }

    @Test
    void 출금되지_않은_확정_청구가_있으면_본인_인증_토큰을_쓰지_않고_409로_막는다() throws Exception {
        long card = card(member.userId(), creditProduct, "4570000011111234", "ACTIVE", 14, account, 1);
        long confirmed = bill(card, "CONFIRMED", LocalDate.of(2026, 11, 16));
        bill(card, "PAID", LocalDate.of(2026, 10, 14));
        String token = stepUp(member, StepUpPurpose.CARD_DELETE);

        delete(member, card, token)
                .andExpect(errorCode(409, "CARD_HAS_OPEN_BILL"))
                .andExpect(jsonPath("$.error.details.billIds.length()").value(1))
                .andExpect(jsonPath("$.error.details.billIds[0]").value(confirmed));
        assertThat(jdbc.queryForObject("SELECT status FROM user_card WHERE user_card_id = ?", String.class, card))
                .isEqualTo("ACTIVE");

        jdbc.update("UPDATE card_bill SET status = 'PAID' WHERE card_bill_id = ?", confirmed);
        delete(member, card, token).andExpect(status().isNoContent());
    }

    @Test
    void 끝나지_않은_청구는_어떤_상태든_해제를_막고_PAID_CANCELED만_통과한다() throws Exception {
        long card = card(member.userId(), creditProduct, "4570000011111234", "ACTIVE", 14, account, 1);
        bill(card, "PAID", LocalDate.of(2026, 9, 14));
        bill(card, "CANCELED", LocalDate.of(2026, 10, 14));
        // 출금 실패처럼 명세에 아직 없는 상태도 끝난 상태가 아니면 막는다.
        long failed = bill(card, "FAILED", LocalDate.of(2026, 11, 16));
        String token = stepUp(member, StepUpPurpose.CARD_DELETE);

        delete(member, card, token)
                .andExpect(errorCode(409, "CARD_HAS_OPEN_BILL"))
                .andExpect(jsonPath("$.error.details.billIds.length()").value(1))
                .andExpect(jsonPath("$.error.details.billIds[0]").value(failed));

        jdbc.update("UPDATE card_bill SET status = 'CANCELED' WHERE card_bill_id = ?", failed);
        delete(member, card, token).andExpect(status().isNoContent());
    }

    // ───────── 기본 카드 조건 ─────────

    @Test
    void 추천에서_뺀_카드는_기본_카드가_될_수_없고_기본_카드를_빼면_지정이_풀린다() throws Exception {
        long inactive = card(member.userId(), creditProduct, "4570000011111234", "INACTIVE", 14, account, 1);
        long active = card(member.userId(), checkProduct, "9410123456785678", "ACTIVE", null, account, 2);

        patch(member, inactive, "{\"isDefault\":true}").andExpect(errorCode(400, "INVALID_REQUEST"));
        // 같은 요청에서 추천 제외와 기본 지정을 함께 보내도 거절하고 아무것도 바꾸지 않는다.
        patch(member, active, "{\"status\":\"INACTIVE\",\"isDefault\":true}").andExpect(errorCode(400, "INVALID_REQUEST"));
        assertThat(jdbc.queryForObject("SELECT status FROM user_card WHERE user_card_id = ?", String.class, active))
                .isEqualTo("ACTIVE");

        patch(member, active, "{\"isDefault\":true}").andExpect(status().isOk());
        patch(member, active, "{\"status\":\"INACTIVE\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isDefault").value(false));
        assertThat(defaults()).isEmpty();
        // 추천에 다시 넣고 기본으로 지정하는 건 한 요청으로 된다.
        patch(member, active, "{\"status\":\"ACTIVE\",\"isDefault\":true}")
                .andExpect(jsonPath("$.data.isDefault").value(true));
    }

    // ───────── 결제일 경계 ─────────

    @Test
    void 결제일은_3개월_되는_날_당일부터_다시_바꿀_수_있다() throws Exception {
        long credit = card(member.userId(), creditProduct, "4570000011111234", "ACTIVE", 14, account, 1);
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        // 한국 날짜로 하루만 덜 지났을 때: 내일부터 가능
        jdbc.update("UPDATE user_card SET payment_day_changed_at = ? WHERE user_card_id = ?",
                kstNoonUtc(today.minusMonths(3).plusDays(1)), credit);
        patch(member, credit, "{\"paymentDay\":20}")
                .andExpect(errorCode(409, "PAYMENT_DAY_CHANGE_LOCKED"))
                .andExpect(jsonPath("$.error.details.changeableFrom").value(today.minusMonths(3).plusDays(1).plusMonths(3).toString()));

        // 꼭 3개월 전에 바꿨으면 오늘 바꿀 수 있다.
        jdbc.update("UPDATE user_card SET payment_day_changed_at = ? WHERE user_card_id = ?",
                kstNoonUtc(today.minusMonths(3)), credit);
        patch(member, credit, "{\"paymentDay\":20}").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.billing.paymentDay").value(20));
    }

    // ───────── 상세 ─────────

    @Test
    void 체크카드_상세는_연결을_해제한_계좌를_출금_계좌로_보여_주지_않는다() throws Exception {
        long check = card(member.userId(), checkProduct, "9410123456785678", "ACTIVE", null, account, 1);
        get(member, "/api/cards/" + check).andExpect(jsonPath("$.data.linkedAccount.accountId").value(account));

        jdbc.update("UPDATE linked_account SET status = 'DISCONNECTED', disconnected_at = ? WHERE linked_account_id = ?",
                now(), account);
        get(member, "/api/cards/" + check)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.linkedAccount").doesNotExist());
    }

    @Test
    void 카드번호를_복호화하지_못한_카드가_있어도_목록은_나오고_그_카드만_끝_4자리로_보인다() throws Exception {
        long good = card(member.userId(), checkProduct, "9410123456785678", "ACTIVE", null, account, 1);
        long broken = card(member.userId(), creditProduct, "4570000011111234", "ACTIVE", 14, account, 2);
        // 다른 칸 이름(AAD)으로 만든 봉투라 카드번호 칸으로는 열리지 않는다.
        jdbc.update("UPDATE user_card SET card_no_enc = ? WHERE user_card_id = ?",
                cipher.encrypt("4570000011111234", CipherField.APP_USER_PHONE), broken);

        get(member, "/api/cards")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cards[0].userCardId").value(good))
                .andExpect(jsonPath("$.data.cards[0].maskedNumber").value("9410-****-****-5678"))
                .andExpect(jsonPath("$.data.cards[1].maskedNumber").value("****-****-****-1234"));
    }

    // ───────── 순서와 새 카드 ─────────

    @Test
    void 순서를_저장한_뒤_새로_등록한_카드는_맨_뒤에_보이고_다음_순서_저장에_포함해야_한다() throws Exception {
        long a = card(member.userId(), checkProduct, "9410123456785678", "ACTIVE", null, account, 1);
        long b = card(member.userId(), creditProduct, "4570000011111234", "ACTIVE", 14, account, 2);
        put(member, "[" + b + "," + a + "]").andExpect(status().isOk());

        long c = card(member.userId(), checkProduct, "9410000022223333", "ACTIVE", null, account, 3);
        get(member, "/api/cards")
                .andExpect(jsonPath("$.data.cards[0].userCardId").value(b))
                .andExpect(jsonPath("$.data.cards[1].userCardId").value(a))
                .andExpect(jsonPath("$.data.cards[2].userCardId").value(c));

        put(member, "[" + b + "," + a + "]").andExpect(errorCode(400, "ORDER_INCOMPLETE"));
        put(member, "[" + c + "," + b + "," + a + "]").andExpect(status().isOk());
        get(member, "/api/cards").andExpect(jsonPath("$.data.cards[0].userCardId").value(c));
    }

    // ───────── 동시 요청 ─────────

    @Test
    void 두_카드를_동시에_기본_카드로_지정해도_기본_카드는_한_장만_남는다() throws Exception {
        long first = card(member.userId(), checkProduct, "9410123456785678", "ACTIVE", null, account, 1);
        long second = card(member.userId(), creditProduct, "4570000011111234", "ACTIVE", 14, account, 2);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        try {
            for (long card : new long[] {first, second}) {
                Callable<Integer> call = () -> {
                    start.await();
                    return patch(member, card, "{\"isDefault\":true}").andReturn().getResponse().getStatus();
                };
                results.add(pool.submit(call));
            }
            start.countDown();
            for (Future<Integer> result : results) {
                assertThat(result.get(30, TimeUnit.SECONDS)).isEqualTo(200);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(defaults()).hasSize(1);
    }

    @Test
    void 두_기기에서_동시에_순서를_저장해도_한쪽_순서만_온전히_남는다() throws Exception {
        long a = card(member.userId(), checkProduct, "9410123456785678", "ACTIVE", null, account, 1);
        long b = card(member.userId(), creditProduct, "4570000011111234", "ACTIVE", 14, account, 2);
        long c = card(member.userId(), checkProduct, "9410000022223333", "ACTIVE", null, account, 3);
        List<Long> first = List.of(c, b, a);
        List<Long> second = List.of(b, a, c);

        List<Integer> statuses = Concurrently.run(List.<Callable<Integer>>of(
                () -> put(member, first.toString()).andReturn().getResponse().getStatus(),
                () -> put(member, second.toString()).andReturn().getResponse().getStatus()));

        assertThat(statuses).containsOnly(200);
        List<Long> saved = jdbc.queryForList(
                "SELECT user_card_id FROM user_card WHERE user_id = ? ORDER BY display_order", Long.class, member.userId());
        // 두 순서가 섞이지 않고 어느 한쪽 순서 그대로다.
        assertThat(saved).isIn(first, second);
        assertThat(jdbc.queryForList("SELECT display_order FROM user_card WHERE user_id = ? ORDER BY display_order",
                Integer.class, member.userId())).containsExactly(0, 1, 2);
    }

    // ───────── 준비 ─────────

    private record Member(long userId, long deviceId) {
    }

    private Member member(String phone) {
        long userId = insert("app_user", "user_id", new HashMap<>(Map.of(
                "name", "김야무",
                "birth_date", LocalDate.of(1999, 3, 15),
                "phone_enc", cipher.encrypt(phone, CipherField.APP_USER_PHONE),
                "phone_hash", lookupHasher.phone(phone),
                "identity_verified_at", now(),
                "pin_hash", "$2a$10$test",
                "pin_updated_at", now(),
                "status", "ACTIVE",
                "created_at", now(),
                "updated_at", now())));
        long deviceId = insert("user_device", "user_device_id", Map.of("user_id", userId,
                "device_uuid", UUID.randomUUID().toString(), "platform", "ANDROID", "public_key", "test-key",
                "key_algorithm", "SHA256withECDSA", "created_at", now(), "updated_at", now()));
        return new Member(userId, deviceId);
    }

    private String stepUp(Member member, StepUpPurpose purpose) {
        long auditLogId = insert("step_up_audit_log", "step_up_audit_log_id", Map.of("user_id", member.userId(),
                "user_device_id", member.deviceId(), "auth_method", "PIN", "purpose", purpose.name(),
                "result", "SUCCESS", "created_at", now()));
        return stepUpTokenStore.issue(new StepUpGrant(member.userId(), member.deviceId(), purpose, AuthMethod.PIN,
                auditLogId));
    }

    private long account(long owner, String last4) {
        String accountNo = "088154114975" + last4;
        return insert("linked_account", "linked_account_id", Map.of("user_id", owner, "bank_code", "088",
                "account_no_enc", cipher.encrypt(accountNo, CipherField.LINKED_ACCOUNT_NO),
                "account_no_hash", lookupHasher.account("088", accountNo), "account_last4", last4,
                "connected_at", now(), "created_at", now(), "updated_at", now()));
    }

    private long product(String issuer, String name, String type, Integer cutoff) {
        Map<String, Object> values = new HashMap<>(Map.of("issuer_code", issuer, "product_name", name,
                "card_type", type, "created_at", now(), "updated_at", now()));
        if (cutoff != null) {
            values.put("billing_cutoff_days", cutoff);
        }
        return insert("card_product", "card_product_id", values);
    }

    /** order가 작을수록 먼저 등록한 카드다(목록 기본 순서는 등록 순). PENDING은 등록 전이라 등록 시각이 없다. */
    private long card(long owner, long product, String cardNo, String status, Integer paymentDay, Long linkedAccount,
            int order) {
        Map<String, Object> values = new HashMap<>(Map.of("user_id", owner, "card_product_id", product,
                "card_no_enc", cipher.encrypt(cardNo, CipherField.USER_CARD_NO),
                "card_no_hash", lookupHasher.card(cardNo), "card_last4", cardNo.substring(12),
                "status", status, "created_at", now(), "updated_at", now()));
        if (!"PENDING".equals(status)) {
            values.put("registered_at", Timestamp.valueOf(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(100 - order)));
        }
        if (paymentDay != null) {
            values.put("payment_day", paymentDay);
        }
        if (linkedAccount != null) {
            values.put("linked_account_id", linkedAccount);
        }
        return insert("user_card", "user_card_id", values);
    }

    private void performance(long card, YearMonth month, long amount) {
        jdbc.update("""
                INSERT INTO card_monthly_performance (user_card_id, performance_month, recognized_amount, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?)
                """, card, Date.valueOf(month.atDay(1)), amount, now(), now());
    }

    /** 확정된 청구서(신용카드). status는 명세 이름(CONFIRMED·UNPAID·PAID). */
    private long bill(long card, String status, LocalDate withdrawalDate) {
        YearMonth month = YearMonth.from(withdrawalDate);
        return insert("card_bill", "card_bill_id", new HashMap<>(Map.ofEntries(
                Map.entry("user_id", member.userId()), Map.entry("user_card_id", card),
                Map.entry("linked_account_id", account), Map.entry("billing_month", Date.valueOf(month.atDay(1))),
                Map.entry("period_start", Date.valueOf(month.minusMonths(1).atDay(2))),
                Map.entry("period_end", Date.valueOf(month.atDay(1))),
                Map.entry("withdrawal_date", Date.valueOf(withdrawalDate)), Map.entry("status", status),
                Map.entry("confirmed_amount", 128500L), Map.entry("confirmed_at", now()),
                Map.entry("created_at", now()), Map.entry("updated_at", now()))));
    }

    /** 진행 중 청구서. 확정 전이라 출금 계좌·확정액은 비어 있다. */
    private long accumulatingBill(long card, BillingCycle.Cycle cycle) {
        return insert("card_bill", "card_bill_id", accumulatingRow(member.userId(), card, cycle));
    }

    private static Map<String, Object> accumulatingRow(long owner, long card, BillingCycle.Cycle cycle) {
        return new HashMap<>(Map.of("user_id", owner, "user_card_id", card,
                "billing_month", Date.valueOf(cycle.billingMonth().atDay(1)),
                "period_start", Date.valueOf(cycle.periodStart()), "period_end", Date.valueOf(cycle.periodEnd()),
                "withdrawal_date", Date.valueOf(cycle.paymentDate()), "status", "ACCUMULATING",
                "created_at", now(), "updated_at", now()));
    }

    private List<Long> defaults() {
        return jdbc.queryForList("SELECT user_card_id FROM user_card WHERE user_id = ? AND is_default = TRUE",
                Long.class, member.userId());
    }

    private long insert(String table, String keyColumn, Map<String, Object> values) {
        return new SimpleJdbcInsert(jdbc).withTableName(table).usingGeneratedKeyColumns(keyColumn)
                .usingColumns(values.keySet().toArray(String[]::new))
                .executeAndReturnKey(values).longValue();
    }

    private ResultActions get(Member member, String path) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.get(path)
                .with(AuthUsers.asUser(member.userId(), member.deviceId())));
    }

    private ResultActions patch(Member member, long card, String json) throws Exception {
        return perform(MockMvcRequestBuilders.patch("/api/cards/" + card)
                .with(AuthUsers.asUser(member.userId(), member.deviceId())), json);
    }

    private ResultActions put(Member member, String ids) throws Exception {
        return perform(MockMvcRequestBuilders.put("/api/cards/order")
                .with(AuthUsers.asUser(member.userId(), member.deviceId())), "{\"userCardIds\":" + ids + "}");
    }

    private ResultActions delete(Member member, long card, String stepUpToken) throws Exception {
        MockHttpServletRequestBuilder builder = MockMvcRequestBuilders.delete("/api/cards/" + card)
                .with(AuthUsers.asUser(member.userId(), member.deviceId()));
        if (stepUpToken != null) {
            builder.header("X-Step-Up-Token", stepUpToken);
        }
        return mockMvc.perform(builder);
    }

    private ResultActions perform(MockHttpServletRequestBuilder builder, String json) throws Exception {
        return mockMvc.perform(builder.contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private static ResultMatcher errorCode(int status, String code) {
        return result -> {
            assertThat(result.getResponse().getStatus()).as("HTTP status").isEqualTo(status);
            assertThat((String) JsonPath.read(result.getResponse().getContentAsString(), "$.error.code")).isEqualTo(code);
        };
    }

    private static LocalDate todayKst() {
        return LocalDate.now(ZoneId.of("Asia/Seoul"));
    }

    private static Timestamp now() {
        return Timestamp.valueOf(LocalDateTime.now(ZoneOffset.UTC));
    }

    /** 한국 날짜 정오를 UTC 시각으로. 결제일 변경 시각은 UTC로 저장하고 한국 날짜로 3개월을 센다. */
    private static Timestamp kstNoonUtc(LocalDate kstDate) {
        return Timestamp.valueOf(kstDate.atTime(12, 0).atZone(ZoneId.of("Asia/Seoul"))
                .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime());
    }
}
