package com.yamu.be.payment.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yamu.be.card.domain.CardType;
import com.yamu.be.payment.approve.ApprovalLedger;
import com.yamu.be.payment.approve.ApprovalOutcome;
import com.yamu.be.payment.cancel.PaymentCancelResponse;
import com.yamu.be.payment.domain.PaymentTransaction;
import com.yamu.be.payment.domain.PaymentTransactionRepository;
import com.yamu.be.payment.recommend.RecommendationResult.BenefitLine;
import com.yamu.be.payment.recommend.RecommendationResult.Candidate;
import com.yamu.be.payment.recommend.RecommendationResult.CardSummary;
import com.yamu.be.payment.recommend.RecommendationResult.ExpectedLimitUse;
import com.yamu.be.payment.recommend.RecommendationResult.RuleVersion;
import com.yamu.be.payment.recommend.RecommendationResult.UnallocatedBenefitGroup;
import com.yamu.be.support.Concurrently;
import com.yamu.be.support.MySqlTestSupport;
import com.yamu.be.support.OwnershipFixtures;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 승인·취소 원장(Task 5.2.5)을 실제 MySQL로 확인한다. 승인 흐름과 떼어 보려고 결제 행은 SQL로 넣고, 원장 구현을 결제 저장(tx2)과 같은
 * 방식(트랜잭션 안에서 결제 행을 읽어 넘김)으로 부른다. 승인 서비스의 재계산·카드 잠금은 {@code PaymentApprovalServiceTest}가 본다.
 */
class PaymentLedgerIntegrationTest extends MySqlTestSupport {

    /** 한국 2026-12-01 00:30 = UTC 11-30 15:30. 사용 월은 한국 날짜라 12월이어야 한다 */
    static final LocalDateTime REQUESTED_AT = LocalDateTime.of(2026, 11, 30, 15, 30);
    static final LocalDate DECEMBER = LocalDate.of(2026, 12, 1);
    static final LocalDate NOVEMBER = LocalDate.of(2026, 11, 1);

    @Autowired JdbcApprovalLedger approvalLedger;
    @Autowired JdbcCancellationLedger cancellationLedger;
    @Autowired PaymentTransactionRepository payments;
    @Autowired TransactionTemplate transaction;
    @Autowired JdbcTemplate jdbc;

    long user;
    long product;
    long otherProduct;
    long card;
    long merchant;
    long discount;
    long point;
    long otherProductBenefit;
    long monthly;
    long daily;
    long otherProductLimit;

    /** 다른 테스트 클래스와 같은 MySQL을 쓰므로 표를 비우지 않는다. 테스트마다 새 사용자·상품·카드를 만들고 그 번호로만 센다. */
    @BeforeEach
    void setUp() {
        OwnershipFixtures fixtures = new OwnershipFixtures(jdbc);
        user = fixtures.user();
        product = fixtures.product();
        otherProduct = fixtures.product();
        card = fixtures.card(user, product, fixtures.account(user), "ACTIVE");
        merchant = merchant();
        discount = benefit(product, "CAFE_DISCOUNT", "DISCOUNT", "KRW", null);
        point = benefit(product, "CAFE_POINT", "POINT", "POINT", new BigDecimal("0.5"));
        otherProductBenefit = benefit(otherProduct, "OTHER", "DISCOUNT", "KRW", null);
        monthly = limitGroup(product, "LIFE_SHARED", "MONTHLY");
        daily = limitGroup(product, "DAILY_CAP", "DAILY");
        otherProductLimit = limitGroup(otherProduct, "OTHER_SHARED", "MONTHLY");
    }

    /**
     * 이 테스트가 만든 원장·혜택·한도 행을 지운다. 남기면 다른 테스트 클래스가 결제·카드·상품을 지울 때 외래 키에 걸린다.
     * 결제·카드·사용자 행은 다른 클래스가 처음에 비우는 표라 남겨도 된다.
     */
    @AfterEach
    void tearDown() {
        List<Long> products = List.of(product, otherProduct);
        for (String table : List.of("payment_benefit", "payment_limit_usage")) {
            jdbc.update("DELETE FROM " + table + " WHERE card_product_id IN (?, ?)", product, otherProduct);
        }
        jdbc.update("DELETE FROM monthly_limit_usage WHERE user_card_id = ?", card);
        jdbc.update("DELETE FROM card_monthly_performance WHERE user_card_id = ?", card);
        for (Long id : products) {
            jdbc.update("DELETE FROM limit_group WHERE card_product_id = ?", id);
            jdbc.update("DELETE FROM card_benefit WHERE card_product_id = ?", id);
        }
    }

    // ───────── 승인 반영 ─────────

    @Test
    void 승인하면_혜택_한도_사용_월_합계를_한국_사용_월로_적고_혜택_행_번호를_돌려준다() {
        long paymentId = approvedPayment(15_000, 0, NOVEMBER);
        Candidate basis = candidate(
                List.of(line(discount, "DISCOUNT", 1_500L, "AT_APPROVAL"), line(point, "POINT", 4L, "POINT_ACCRUAL")),
                List.of(use(monthly, "MONTHLY", 5_000, 1_500), use(daily, "DAILY", 3_000, 1_500)), List.of());

        ApprovalLedger.Recorded recorded = record(paymentId, approved(13_500, 0, basis));

        assertThat(recorded.paymentBenefitIds()).containsOnlyKeys(discount, point);
        Map<String, Object> cafe = row("SELECT * FROM payment_benefit WHERE card_benefit_id = ?", discount);
        assertThat(cafe.get("payment_benefit_id")).isEqualTo(recorded.paymentBenefitIds().get(discount));
        assertThat((BigDecimal) cafe.get("benefit_value")).isEqualByComparingTo("1500");
        assertThat(cafe.get("value_unit")).isEqualTo("KRW");
        assertThat(cafe.get("krw_amount")).isEqualTo(1_500L);
        // 4P × 0.5 = 2원: 정수라 원화 환산액이 있다
        Map<String, Object> points = row("SELECT * FROM payment_benefit WHERE card_benefit_id = ?", point);
        assertThat(points.get("value_unit")).isEqualTo("POINT");
        assertThat(points.get("krw_amount")).isEqualTo(2L);

        Map<String, Object> monthlyUse = row("SELECT * FROM payment_limit_usage WHERE limit_group_id = ?", monthly);
        assertThat(monthlyUse.get("remaining_before")).isEqualTo(5_000L);
        assertThat(monthlyUse.get("consumed_value")).isEqualTo(1_500L);
        assertThat(monthlyUse.get("usage_date")).isEqualTo(java.sql.Date.valueOf(LocalDate.of(2026, 12, 1)));
        assertThat(monthlyUse.get("usage_month")).isEqualTo(java.sql.Date.valueOf(DECEMBER));
        assertThat(monthlyUse.get("restored_at")).isNull();
        assertThat(count("SELECT COUNT(*) FROM payment_limit_usage WHERE limit_group_id = ?", daily)).isEqualTo(1);
        // 월 합계는 MONTHLY 그룹만, 한국 사용 월(12월)에 만든다. DAILY는 일별 합계 테이블이 없다
        assertThat(monthlyUsed(monthly, DECEMBER)).isEqualTo(1_500L);
        assertThat(count("SELECT COUNT(*) FROM monthly_limit_usage WHERE limit_group_id = ?", daily)).isZero();
    }

    @Test
    void 포인트_원화_환산액이_소수면_NULL_미환산으로_적고_포인트_값은_그대로_둔다() {
        long paymentId = approvedPayment(15_000, 0, NOVEMBER);
        // 3P × 0.5 = 1.5원
        Candidate basis = candidate(List.of(line(point, "POINT", 3L, "POINT_ACCRUAL")), List.of(), List.of());

        record(paymentId, approved(15_000, 0, basis));

        Map<String, Object> points = row("SELECT * FROM payment_benefit WHERE card_benefit_id = ?", point);
        assertThat((BigDecimal) points.get("benefit_value")).isEqualByComparingTo("3");
        assertThat(points.get("krw_amount")).isNull();
    }

    @Test
    void 같은_카드_다른_결제의_사용량은_월_합계에_더해진다() {
        long first = approvedPayment(15_000, 0, NOVEMBER);
        long second = approvedPayment(15_000, 0, NOVEMBER);

        record(first, approved(15_000, 0, candidate(List.of(), List.of(use(monthly, "MONTHLY", 5_000, 1_500)),
                List.of())));
        record(second, approved(15_000, 0, candidate(List.of(), List.of(use(monthly, "MONTHLY", 3_500, 2_000)),
                List.of())));

        assertThat(monthlyUsed(monthly, DECEMBER)).isEqualTo(3_500L);
    }

    @Test
    void 같은_카드의_두_승인을_동시에_적어도_월_합계를_잃지_않는다() throws Exception {
        List<Long> paymentIds = List.of(approvedPayment(15_000, 0, NOVEMBER), approvedPayment(15_000, 0, NOVEMBER),
                approvedPayment(15_000, 0, NOVEMBER), approvedPayment(15_000, 0, NOVEMBER));

        Concurrently.run(paymentIds.stream().<Callable<ApprovalLedger.Recorded>>map(id -> () -> record(id,
                approved(15_000, 0, candidate(List.of(), List.of(use(monthly, "MONTHLY", 10_000, 1_000)),
                        List.of())))).toList());

        assertThat(monthlyUsed(monthly, DECEMBER)).isEqualTo(4_000L);
        assertThat(count("SELECT COUNT(*) FROM payment_limit_usage WHERE limit_group_id = ?", monthly)).isEqualTo(4);
    }

    @Test
    void 금액이_0원이거나_미확정인_혜택_줄과_사용량_0은_적지_않는다() {
        long paymentId = approvedPayment(15_000, 0, NOVEMBER);
        // 공유 한도 배분 미확정 묶음: 상세 금액 NULL, 그룹 사용량은 expectedLimitUses에 있다
        Candidate basis = candidate(
                List.of(line(discount, "DISCOUNT", null, "AT_BILLING"), line(point, "POINT", null, "POINT_ACCRUAL")),
                List.of(use(monthly, "MONTHLY", 5_000, 1_800), use(daily, "DAILY", 3_000, 0)),
                List.of(new UnallocatedBenefitGroup(List.of(discount, point), monthly, 1_800, "KRW",
                        "SHARED_LIMIT_ALLOCATION_UNRESOLVED")));

        ApprovalLedger.Recorded recorded = record(paymentId, approved(15_000, 0, basis));

        assertThat(recorded.paymentBenefitIds()).isEmpty();
        assertThat(count("SELECT COUNT(*) FROM payment_benefit WHERE card_product_id = ?", product)).isZero();
        assertThat(count("SELECT COUNT(*) FROM payment_limit_usage WHERE card_product_id = ?", product)).isEqualTo(1);
        assertThat(monthlyUsed(monthly, DECEMBER)).isEqualTo(1_800L);
    }

    @Test
    void 사용량이_사용_직전_남은_한도보다_크면_남은_한도만큼만_적는다() {
        long paymentId = approvedPayment(15_000, 0, NOVEMBER);

        record(paymentId, approved(15_000, 0, candidate(List.of(), List.of(use(monthly, "MONTHLY", 1_000, 1_500)),
                List.of())));

        assertThat(row("SELECT * FROM payment_limit_usage WHERE limit_group_id = ?", monthly).get("consumed_value"))
                .isEqualTo(1_000L);
        assertThat(monthlyUsed(monthly, DECEMBER)).isEqualTo(1_000L);
    }

    @Test
    void 다른_상품의_혜택과_한도_그룹은_적지_않는다() {
        long paymentId = approvedPayment(15_000, 0, NOVEMBER);

        record(paymentId, approved(15_000, 0, candidate(List.of(line(otherProductBenefit, "DISCOUNT", 500L,
                "AT_BILLING")), List.of(use(otherProductLimit, "MONTHLY", 5_000, 500)), List.of())));

        assertThat(count("SELECT COUNT(*) FROM payment_benefit WHERE card_product_id = ?", product)).isZero();
        assertThat(count("SELECT COUNT(*) FROM payment_limit_usage WHERE card_product_id = ?", product)).isZero();
        assertThat(count("SELECT COUNT(*) FROM monthly_limit_usage WHERE user_card_id = ?", card)).isZero();
    }

    @Test
    void 실적_인정액이_있으면_실적_월에_더하고_0원이면_행을_만들지_않는다() {
        long zero = approvedPayment(15_000, 0, NOVEMBER);
        record(zero, approved(15_000, 0, null));
        assertThat(count("SELECT COUNT(*) FROM card_monthly_performance WHERE user_card_id = ?", card)).isZero();

        long first = approvedPayment(15_000, 12_000, NOVEMBER);
        long second = approvedPayment(15_000, 3_000, NOVEMBER);
        record(first, approved(15_000, 12_000, null));
        record(second, approved(15_000, 3_000, null));

        assertThat(performance(NOVEMBER)).isEqualTo(15_000L);
    }

    @Test
    void 원장_반영이_실패하면_그_트랜잭션의_원장_행이_모두_롤백된다() {
        long paymentId = approvedPayment(15_000, 0, NOVEMBER);
        Candidate basis = candidate(List.of(line(discount, "DISCOUNT", 1_500L, "AT_APPROVAL")),
                List.of(use(monthly, "MONTHLY", 5_000, 1_500)), List.of());
        record(paymentId, approved(13_500, 0, basis));

        // 같은 결제를 다시 적으면 UNIQUE(결제·혜택)에 걸린다. 앞서 적은 값 외에 아무것도 남지 않아야 한다
        assertThatThrownBy(() -> record(paymentId, approved(13_500, 0, basis)))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(count("SELECT COUNT(*) FROM payment_benefit WHERE card_product_id = ?", product)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM payment_limit_usage WHERE card_product_id = ?", product)).isEqualTo(1);
        assertThat(monthlyUsed(monthly, DECEMBER)).isEqualTo(1_500L);
    }

    // ───────── 취소 복구 ─────────

    @Test
    void 취소하면_원래_월에서_실적과_월_한도를_빼고_복구_시각을_적는다() {
        long paymentId = approvedPayment(15_000, 15_000, NOVEMBER);
        record(paymentId, approved(13_500, 15_000, candidate(List.of(line(discount, "DISCOUNT", 1_500L, "AT_APPROVAL")),
                List.of(use(monthly, "MONTHLY", 5_000, 1_500), use(daily, "DAILY", 3_000, 1_500)), List.of())));
        jdbc.update("UPDATE card_monthly_performance SET recognized_amount = 40000 WHERE user_card_id = ?", card);
        cancel(paymentId);

        PaymentCancelResponse.Restored restored = reverse(paymentId);

        assertThat(restored.performance()).isEqualTo(new PaymentCancelResponse.Performance("2026-11", 15_000));
        assertThat(restored.limitImpacts()).containsExactlyInAnyOrder(
                new PaymentCancelResponse.LimitImpact(monthly, "MONTHLY", "2026-12", 1_500),
                new PaymentCancelResponse.LimitImpact(daily, "DAILY", "2026-12", 1_500));
        assertThat(performance(NOVEMBER)).isEqualTo(25_000L);
        assertThat(monthlyUsed(monthly, DECEMBER)).isZero();
        assertThat(count("SELECT COUNT(*) FROM payment_limit_usage WHERE card_product_id = ? AND restored_at IS NULL", product)).isZero();
        assertThat(payments.findById(paymentId).orElseThrow().getPerformanceReversedAt()).isNotNull();
        // 받은 혜택 기록은 지우지 않는다(취소 여부는 결제 상태로 본다)
        assertThat(count("SELECT COUNT(*) FROM payment_benefit WHERE card_product_id = ?", product)).isEqualTo(1);
    }

    @Test
    void 이미_되돌린_결제를_다시_되돌려도_두_번_빼지_않는다() {
        long paymentId = approvedPayment(15_000, 15_000, NOVEMBER);
        record(paymentId, approved(15_000, 15_000, candidate(List.of(), List.of(use(monthly, "MONTHLY", 5_000, 1_500)),
                List.of())));
        jdbc.update("UPDATE monthly_limit_usage SET used_value = 4000 WHERE limit_group_id = ?", monthly);
        jdbc.update("UPDATE card_monthly_performance SET recognized_amount = 40000 WHERE user_card_id = ?", card);
        cancel(paymentId);

        reverse(paymentId);
        PaymentCancelResponse.Restored again = reverse(paymentId);

        assertThat(again.performance()).isNull();
        assertThat(again.limitImpacts()).isEmpty();
        assertThat(monthlyUsed(monthly, DECEMBER)).isEqualTo(2_500L);
        assertThat(performance(NOVEMBER)).isEqualTo(25_000L);
    }

    @Test
    void 지난달_결제를_이번_달에_취소해도_원래_달에서_뺀다() {
        long paymentId = approvedPayment(15_000, 10_000, NOVEMBER);
        record(paymentId, approved(15_000, 10_000, null));
        // 이번 달(12월) 실적 행도 있다. 건드리지 않아야 한다
        jdbc.update("INSERT INTO card_monthly_performance (user_card_id, performance_month, recognized_amount, "
                + "created_at, updated_at) VALUES (?, ?, 7000, NOW(6), NOW(6))", card, DECEMBER);
        cancel(paymentId);

        reverse(paymentId);

        assertThat(performance(NOVEMBER)).isZero();
        assertThat(performance(DECEMBER)).isEqualTo(7_000L);
    }

    @Test
    void 합계_행이_없거나_모자라도_0_아래로_내리지_않고_취소를_막지_않는다() {
        long paymentId = approvedPayment(15_000, 15_000, NOVEMBER);
        record(paymentId, approved(15_000, 15_000, candidate(List.of(), List.of(use(monthly, "MONTHLY", 5_000, 1_500)),
                List.of())));
        jdbc.update("UPDATE monthly_limit_usage SET used_value = 500 WHERE limit_group_id = ?", monthly);
        jdbc.update("DELETE FROM card_monthly_performance WHERE user_card_id = ?", card);
        cancel(paymentId);

        PaymentCancelResponse.Restored restored = reverse(paymentId);

        assertThat(restored.limitImpacts()).hasSize(1);
        assertThat(monthlyUsed(monthly, DECEMBER)).isZero();
        assertThat(count("SELECT COUNT(*) FROM card_monthly_performance WHERE user_card_id = ?", card)).isZero();
    }

    // ───────── 원장 기준 정합성 ─────────

    @Test
    void 승인과_취소를_섞어도_월_한도_합계는_복구되지_않은_사용_내역의_합과_같다() {
        long a = approvedPayment(15_000, 0, NOVEMBER);
        long b = approvedPayment(15_000, 0, NOVEMBER);
        long c = approvedPayment(15_000, 0, NOVEMBER);
        record(a, approved(15_000, 0, candidate(List.of(), List.of(use(monthly, "MONTHLY", 10_000, 1_000)), List.of())));
        record(b, approved(15_000, 0, candidate(List.of(), List.of(use(monthly, "MONTHLY", 9_000, 2_000)), List.of())));
        record(c, approved(15_000, 0, candidate(List.of(), List.of(use(monthly, "MONTHLY", 7_000, 3_000)), List.of())));
        cancel(b);
        reverse(b);

        Long fromLedger = jdbc.queryForObject("""
                SELECT COALESCE(SUM(consumed_value), 0) FROM payment_limit_usage
                WHERE limit_group_id = ? AND usage_month = ? AND restored_at IS NULL
                """, Long.class, monthly, DECEMBER);
        assertThat(monthlyUsed(monthly, DECEMBER)).isEqualTo(fromLedger).isEqualTo(4_000L);
    }

    // ───────── 도우미 ─────────

    private ApprovalLedger.Recorded record(long paymentId, ApprovalOutcome.Approved outcome) {
        return transaction.execute(status -> approvalLedger.recordApproval(payments.findById(paymentId).orElseThrow(),
                outcome));
    }

    private PaymentCancelResponse.Restored reverse(long paymentId) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return transaction.execute(status -> cancellationLedger.reverse(payments.findById(paymentId).orElseThrow(),
                now));
    }

    private ApprovalOutcome.Approved approved(long amount, long recognized, Candidate basis) {
        return new ApprovalOutcome.Approved(amount, amount, recognized, NOVEMBER,
                ApprovalOutcome.Approved.applied(basis), null, null, null, basis);
    }

    private Candidate candidate(List<BenefitLine> lines, List<ExpectedLimitUse> uses,
            List<UnallocatedBenefitGroup> groups) {
        return new Candidate(1, new CardSummary(card, product, CardType.CREDIT, false, null, "1234", "TEST", "테스트 카드"),
                BigDecimal.ZERO, lines, null, null, new RuleVersion(product, 1), groups, uses);
    }

    private static BenefitLine line(long benefitId, String type, Long amount, String realization) {
        return new BenefitLine(benefitId, "혜택 " + benefitId, type, BigDecimal.valueOf(amount == null ? 0 : amount),
                amount, realization);
    }

    private static ExpectedLimitUse use(long limitGroupId, String period, long remainingBefore, long consumed) {
        return new ExpectedLimitUse(limitGroupId, 1, "KRW", period, BigDecimal.valueOf(remainingBefore),
                BigDecimal.valueOf(consumed));
    }

    private long approvedPayment(long requested, long recognized, LocalDate performanceMonth) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        UUID a = UUID.randomUUID();
        Map<String, Object> row = new HashMap<>();
        row.put("user_id", user);
        row.put("user_card_id", card);
        row.put("card_product_id", product);
        row.put("merchant_id", merchant);
        row.put("request_key", UUID.randomUUID().toString());
        row.put("request_fingerprint", ByteBuffer.allocate(32).putLong(a.getMostSignificantBits())
                .putLong(a.getLeastSignificantBits()).array());
        row.put("source", "YAMU");
        row.put("status", "APPROVED");
        row.put("requested_amount", requested);
        row.put("approved_amount", requested);
        row.put("installment_months", 1);
        row.put("recognized_performance_amount", recognized);
        row.put("performance_month", performanceMonth);
        row.put("requested_at", REQUESTED_AT);
        row.put("approved_at", REQUESTED_AT.plusSeconds(10));
        row.put("created_at", now);
        row.put("updated_at", now);
        return insert("payment_transaction", "payment_transaction_id", row);
    }

    private void cancel(long paymentId) {
        jdbc.update("UPDATE payment_transaction SET status = 'CANCELED', canceled_at = ? WHERE payment_transaction_id = ?",
                LocalDateTime.now(ZoneOffset.UTC), paymentId);
    }

    private long benefit(long productId, String code, String type, String unit, BigDecimal pointRate) {
        Map<String, Object> row = new HashMap<>();
        row.put("card_product_id", productId);
        row.put("benefit_code", code);
        row.put("policy_version", 1);
        row.put("benefit_name", code);
        row.put("benefit_type", type);
        row.put("calculation_type", "RATE");
        row.put("rate", new BigDecimal("0.1"));
        row.put("value_unit", unit);
        if (pointRate != null) {
            row.put("point_krw_rate", pointRate);
        }
        row.put("created_at", LocalDateTime.now(ZoneOffset.UTC));
        return insert("card_benefit", "card_benefit_id", row);
    }

    private long limitGroup(long productId, String code, String period) {
        return insert("limit_group", "limit_group_id", Map.of("card_product_id", productId, "group_code", code,
                "policy_version", 1, "group_name", code, "period_type", period, "limit_unit", "KRW",
                "base_limit_value", 10_000L, "created_at", LocalDateTime.now(ZoneOffset.UTC)));
    }

    private long merchant() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return insert("merchant", "merchant_id", Map.of("merchant_name", "테스트 카페", "brand_code", "LEDGER_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                "merchant_category_code", "COFFEE_SHOP", "created_at", now, "updated_at", now));
    }

    private Long monthlyUsed(long limitGroupId, LocalDate month) {
        List<Long> used = jdbc.queryForList(
                "SELECT used_value FROM monthly_limit_usage WHERE user_card_id = ? AND limit_group_id = ? AND usage_month = ?",
                Long.class, card, limitGroupId, month);
        return used.isEmpty() ? null : used.get(0);
    }

    private Long performance(LocalDate month) {
        List<Long> amount = jdbc.queryForList(
                "SELECT recognized_amount FROM card_monthly_performance WHERE user_card_id = ? AND performance_month = ?",
                Long.class, card, month);
        return amount.isEmpty() ? null : amount.get(0);
    }

    private Map<String, Object> row(String sql, Object... args) {
        return jdbc.queryForMap(sql, args);
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    private long insert(String table, String idColumn, Map<String, Object> row) {
        return new SimpleJdbcInsert(jdbc).withTableName(table).usingGeneratedKeyColumns(idColumn)
                .usingColumns(row.keySet().toArray(String[]::new))
                .executeAndReturnKey(row).longValue();
    }
}
