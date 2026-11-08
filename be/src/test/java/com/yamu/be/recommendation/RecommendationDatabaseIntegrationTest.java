package com.yamu.be.recommendation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.util.ReflectionTestUtils.getField;
import static org.springframework.test.util.ReflectionTestUtils.setField;

import com.yamu.be.card.CardActivityReader;
import com.yamu.be.card.domain.*;
import com.yamu.be.recommendation.RecommendationCommand.PaymentAvailability;
import com.yamu.be.recommendation.RecommendationInput.*;
import com.yamu.be.recommendation.domain.*;
import com.yamu.be.support.MySqlTestSupport;
import com.yamu.be.support.OwnershipFixtures;
import java.sql.Date;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** 실제 Flyway·JPA·일 집계·기존 Reader·Selector를 연결한다. 상품 정책의 완전성을 가정하지 않는다. */
@TestPropertySource(properties = "spring.application.name=yamu-recommendation-database-test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RecommendationDatabaseIntegrationTest extends MySqlTestSupport {
    @Autowired JdbcTemplate jdbc;
    @Autowired UserCardRepository cards;
    @Autowired CardBenefitRepository benefits;
    @Autowired PaymentLimitUsageRepository daily;
    @Autowired MonthlyLimitUsageRepository monthly;
    @Autowired RecommendationPolicyLoader loader;
    @Autowired RecommendationService service;
    @Autowired CardActivityReader activity;
    @MockitoSpyBean RecommendationInputAssembler assembler;
    long user, otherUser, product, otherProduct, first, second, foreign, merchant, benefit, shared;
    LocalDate date = LocalDate.of(2026, 10, 1);

    @BeforeEach
    void seed() {
        var fixtures = new OwnershipFixtures(jdbc);
        user = fixtures.user();
        otherUser = fixtures.user();
        product = fixtures.product();
        otherProduct = fixtures.product();
        first = fixtures.card(user, product, fixtures.account(user), "ACTIVE");
        second = fixtures.card(user, product, fixtures.account(user), "ACTIVE");
        jdbc.update("UPDATE user_card SET payment_day=15 WHERE user_id=?",user);
        foreign = fixtures.card(otherUser, product, null, "ACTIVE");
        merchant = insert("merchant", "merchant_id", Map.of("brand_code", "TEST_" + UUID.randomUUID(),
                "merchant_name", "테스트 카페", "merchant_category_code", "CAFE", "updated_at", now()));
        benefit = benefit("MAIN", 1, LocalDate.of(2026, 1, 1), BenefitPolicy.NO_END_DATE, product);
        shared = group("MONTHLY", "KRW");
        long target = insert("benefit_target", "benefit_target_id", Map.of("card_benefit_id", benefit, "target_type", "CATEGORY", "target_value", "CAFE"));
        long targetGroup = insert("benefit_target_group", "benefit_target_group_id", Map.of("card_benefit_id", benefit, "group_code", "FIXTURE_CAFE", "is_excluded", false));
        jdbc.update("INSERT INTO benefit_target_group_member(benefit_target_group_id,benefit_target_id,card_benefit_id) VALUES(?,?,?)", targetGroup, target, benefit);
        link(benefit, shared);
        performance(first, "2026-09-01", 0);
        performance(first, "2026-10-01", 999_999);
    }

    @AfterEach
    void cleanup() {
        // 이 테스트에서 생성한 상품·사용자만 제거한다. 서비스 REQUIRES_NEW가 읽도록 fixture는 먼저 커밋한다.
        for (long id : new long[] {product, otherProduct}) {
            jdbc.update("delete from payment_limit_usage where card_product_id = ?", id);
            jdbc.update("delete from monthly_limit_usage where card_product_id = ?", id);
            jdbc.update("delete from payment_transaction where card_product_id = ?", id);
        }
        jdbc.update("delete from card_monthly_performance where user_card_id in (select user_card_id from user_card where user_id in (?, ?))", user, otherUser);
        jdbc.update("delete from user_card where user_id in (?, ?)", user, otherUser);
        jdbc.update("delete from linked_account where user_id in (?, ?)", user, otherUser);
        for (long id : new long[] {product, otherProduct}) {
            jdbc.update("delete from benefit_target_group_member where card_benefit_id in (select card_benefit_id from card_benefit where card_product_id = ?)", id);
            jdbc.update("delete from benefit_target_group where card_benefit_id in (select card_benefit_id from card_benefit where card_product_id = ?)", id);
            jdbc.update("delete from benefit_target where card_benefit_id in (select card_benefit_id from card_benefit where card_product_id = ?)", id);
            jdbc.update("delete from benefit_limit_link where card_product_id = ?", id);
            jdbc.update("delete from tier_limit where limit_group_id in (select limit_group_id from limit_group where card_product_id = ?)", id);
            jdbc.update("delete from limit_group where card_product_id = ?", id);
            jdbc.update("delete from card_benefit where card_product_id = ?", id);
            jdbc.update("delete from card_product where card_product_id = ?", id);
        }
        jdbc.update("delete from merchant where merchant_id = ?", merchant);
        jdbc.update("delete from app_user where user_id in (?, ?)", user, otherUser);
    }

    @Test
    void jpaFiltersProductAndHalfOpenDatesWithoutDroppingUnknownDatesOrChoosingHighestVersion() {
        long unknown = benefit("MAIN", 2, null, null, product);
        benefit("FUTURE", 1, date.plusDays(1), BenefitPolicy.NO_END_DATE, product);
        benefit("EXPIRED", 1, date.minusMonths(1), date, product);
        benefit("FOREIGN_PRODUCT", 1, date.minusMonths(1), BenefitPolicy.NO_END_DATE, otherProduct);
        var rows = benefits.findPossiblyEffective(List.of(product), date, BenefitPolicy.NO_END_DATE);
        assertThat(rows).extracting(CardBenefit::getId).containsExactly(benefit, unknown);
        var owned = cards.findOrdered(user, List.of(CardRegistrationStatus.ACTIVE, CardRegistrationStatus.INACTIVE));
        assertThat(owned).extracting(UserCard::getId).containsExactly(first, second).doesNotContain(foreign);
        var issues = new ArrayList<RecommendationDataIssue>();
        var converted = assembler.policy(owned.get(0), loader.load(user, owned, date), date, 0L, issues);
        assertThat(converted.benefits()).hasSize(2);
        assertThat(issues).extracting(RecommendationDataIssue::code).contains("BENEFIT_VERSION_AMBIGUOUS");
    }

    @Test
    void realServiceUsesTransactionPreviousMonthPreservesMissingAndLeavesAggregatesUnchanged() {
        usage(first, shared, "2026-10-01", 0L);
        usage(second, shared, "2026-09-01", 2_000L);
        var before = snapshot();
        var result = service.recommend(command(Map.of(first, available(), second, available())));
        assertThat(result.recommendation().status()).isEqualTo(RecommendationSelector.Status.DATA_INSUFFICIENT);
        assertThat(result.recommendation().uncomparedCards()).hasSize(2);
        verify(assembler).policy(argThat(c -> c.getId() == first), any(), eq(date), eq(0L), anyList(), any(), any());
        verify(assembler).policy(argThat(c -> c.getId() == second), any(), eq(date), isNull(), anyList(), any(), any());
        assertThat(result.issues()).anyMatch(i -> i.userCardId() == second && i.code().equals("PREVIOUS_PERFORMANCE_MISSING"));
        assertThat(result.issues()).noneMatch(RecommendationDataIssue::retryable);
        assertThat(snapshot()).isEqualTo(before);
        assertThat(activity.recognizedAmounts(List.of(first), YearMonth.of(2026, 10))).containsEntry(first, 999_999L);
    }

    @Test
    void dailyUsageGroupsSharedConsumptionOnceAndFiltersUserDateGroupAndRestoredRecords() {
        long dayGroup = group("DAILY", "COUNT");
        link(benefit, dayGroup);
        consumed(first, user, dayGroup, date, 1, false);
        consumed(first, user, dayGroup, date, 2, false);
        consumed(first, user, dayGroup, date, 100, true);
        consumed(first, user, dayGroup, date.minusDays(1), 200, false);
        consumed(foreign, otherUser, dayGroup, date, 400, false);
        consumed(second, user, dayGroup, date, 800, false);
        consumed(first, user, shared, date, 1_600, false);
        var rows = daily.sumDaily(user, List.of(first, foreign), List.of(dayGroup), date, date.withDayOfMonth(1));
        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r.getUserCardId()).isEqualTo(first);
            assertThat(r.getLimitGroupId()).isEqualTo(dayGroup);
            assertThat(r.getUsedValue()).isEqualByComparingTo("3");
        });
        assertThat(daily.sumDaily(user, List.of(first), List.of(dayGroup), date, date.minusMonths(1).withDayOfMonth(1))).isEmpty();
        assertDailyInput(dayGroup, 3L);
    }

    @Test
    void noDailyRecordsPassZeroThroughRepositoryLoaderAndAssembler() {
        long dayGroup = group("DAILY", "KRW");
        link(benefit, dayGroup);
        assertThat(daily.sumDaily(user, List.of(first), List.of(dayGroup), date, date.withDayOfMonth(1))).isEmpty();
        assertDailyInput(dayGroup, 0L);
    }

    @Test
    void allDailyRecordsRestoredPassZeroThroughRepositoryLoaderAndAssembler() {
        long dayGroup = group("DAILY", "KRW");
        link(benefit, dayGroup);
        consumed(first, user, dayGroup, date, 100, true);
        consumed(first, user, dayGroup, date, 200, true);
        assertThat(daily.sumDaily(user, List.of(first), List.of(dayGroup), date, date.withDayOfMonth(1))).isEmpty();
        assertDailyInput(dayGroup, 0L);
    }

    private void assertDailyInput(long dayGroup, long expected) {
        var owned = cards.findOrdered(user, List.of(CardRegistrationStatus.ACTIVE));
        var data = loader.load(user, owned, date);
        var issues = new ArrayList<RecommendationDataIssue>();
        var policy = assembler.policy(owned.stream().filter(c -> c.getId() == first).findFirst().orElseThrow(),
                data, date, null, issues);
        assertThat(policy.limits()).filteredOn(l -> l.id() == dayGroup).singleElement()
                .satisfies(l -> assertThat(l.used()).isEqualTo(expected));
        // S15P31E103-324: 정상 조회에서 그 달 월간 행이 없으면 일별과 같이 사용량 0이다
        assertThat(policy.limits()).filteredOn(l -> l.id() == shared).singleElement()
                .satisfies(l -> assertThat(l.used()).isZero());
        assertThat(issues).noneMatch(i -> i.code().equals("LIMIT_USAGE_MISSING") && i.policyId() == dayGroup);
    }

    @Test
    void sharedMonthlyLimitAndTierUseStoredVersionsAndDoNotDoubleCountDailyDetail() {
        long another = benefit("SECOND", 1, date.minusMonths(1), BenefitPolicy.NO_END_DATE, product);
        link(another, shared);
        jdbc.update("update limit_group set base_limit_value = null where limit_group_id = ?", shared);
        insert("tier_limit", "tier_limit_id", Map.of("limit_group_id", shared, "min_performance_amount", 0L,
                "max_performance_amount", 300_000L, "limit_value", 1_000L));
        insert("tier_limit", "tier_limit_id", Map.of("limit_group_id", shared, "min_performance_amount", 300_000L, "limit_value", 5_000L));
        usage(first, shared, "2026-10-01", 0L);
        usage(first, shared, "2026-09-01", 999L);
        consumed(first, user, shared, date, 300, false);
        var owned = cards.findOrdered(user, List.of(CardRegistrationStatus.ACTIVE));
        var data = loader.load(user, owned, date);
        var converted = assembler.policy(owned.get(0), data, date, 300_000L, new ArrayList<>());
        assertThat(converted.benefits()).hasSize(2).allSatisfy(b -> assertThat(b.limitIds()).containsExactly(shared));
        assertThat(converted.limits()).singleElement().satisfies(l -> {
            assertThat(l.maximum()).isEqualTo(5_000L);
            assertThat(l.used()).isZero();
            assertThat(l.version()).isEqualTo(1);
        });
        // 두 번째 카드는 이번 달 행이 없다(S15P31E103-324: 아직 쓰지 않은 0)
        var missing = assembler.policy(owned.get(1), data, date, null, new ArrayList<>());
        assertThat(missing.limits().get(0).used()).isZero();
        assertThat(data.daily()).isEmpty(); // 월간 그룹의 상세 기록을 집계에 다시 더하지 않는다.
    }

    @Test
    void realServiceRejectsAnotherUsersCardAndKeepsInactiveCardVisibleAsExcluded() {
        assertThatIllegalArgumentException().isThrownBy(() -> service.recommend(command(Map.of(foreign, available()))));
        jdbc.update("update user_card set status = 'INACTIVE' where user_card_id = ?", second);
        var result = service.recommend(command(Map.of(first, available()))).recommendation();
        assertThat(result.evaluatedCards()).filteredOn(r -> r.userCardId() == second).singleElement()
                .satisfies(r -> assertThat(r.status()).isEqualTo(RecommendationSelector.CardStatus.EXCLUDED));
    }

    @Test
    void realServiceConnectsSourcedRecognitionAndExactMonthAggregateToSelectorWithoutStubbingConversion() {
        jdbc.update("update card_product set issuer_code = 'SHINHAN', product_name = '신한카드 처음' where card_product_id = ?", product);
        jdbc.update("update card_monthly_performance set recognized_amount = 280000 where user_card_id = ? and performance_month = '2026-10-01'", first);
        performance(second, "2026-10-01", 290_000L);
        performance(second, "2026-09-01", 777_777L);
        performance(foreign, "2026-10-01", 888_888L);
        var before = snapshot();
        var captured = new ArrayList<RecommendationInput>();
        var original = getField(service, "selector");
        var selector = spy(new RecommendationSelector());
        doAnswer(invocation -> {
            captured.add(invocation.getArgument(0));
            return invocation.callRealMethod();
        }).when(selector).select(any());
        setField(service, "selector", selector);
        try {
            var request = new RecommendationCommand(100, user, new Transaction(10_000, Plan.LUMP_SUM, 1,
                    Instant.parse("2026-09-30T15:30:00Z"), merchant, "COFFEE_SHOP",
                    RecognitionFixtures.ordinaryDomestic("shinhan-cheoeum")), Map.of(first, available(), second, available()));
            var result = service.recommend(request);
            assertThat(result.recommendation().status()).isEqualTo(RecommendationSelector.Status.DATA_INSUFFICIENT);
            var input = captured.get(0);
            assertThat(input.cards()).hasSize(2).allSatisfy(c -> {
                assertThat(c.performance().period()).isEqualTo(YearMonth.of(2026, 10));
                assertThat(c.performance().minimumAmount()).isEqualTo(300_000L);
                assertThat(c.performance().recognizedAmount()).isEqualTo(10_000L);
            });
            assertThat(input.cards()).filteredOn(c -> c.userCardId() == first).singleElement()
                    .satisfies(c -> assertThat(c.performance().beforeAmount()).isEqualTo(280_000L));
            assertThat(input.cards()).filteredOn(c -> c.userCardId() == second).singleElement()
                    .satisfies(c -> assertThat(c.performance().beforeAmount()).isEqualTo(290_000L));
            assertThat(snapshot()).isEqualTo(before);
        } finally {
            setField(service, "selector", original);
        }
    }

    private long benefit(String code, int version, LocalDate from, LocalDate until, long productId) {
        var fields = new HashMap<String, Object>(Map.of("card_product_id", productId, "benefit_code", code,
                "policy_version", version, "benefit_name", "검증용 혜택", "benefit_type", "DISCOUNT",
                "calculation_type", "RATE", "rate", new java.math.BigDecimal("0.05"), "value_unit", "KRW",
                "min_performance_amount", 300_000L));
        fields.put("valid_from", from);
        fields.put("valid_until", until);
        return insert("card_benefit", "card_benefit_id", fields);
    }

    private long group(String period, String unit) {
        return insert("limit_group", "limit_group_id", Map.of("card_product_id", product, "group_code", period,
                "group_name", "공유 한도", "policy_version", 1, "period_type", period, "limit_unit", unit,
                "base_limit_value", 5_000L, "valid_from", date.minusYears(1), "valid_until", BenefitPolicy.NO_END_DATE));
    }

    private void link(long benefitId, long groupId) {
        insert("benefit_limit_link", "benefit_limit_link_id", Map.of("card_product_id", product,
                "card_benefit_id", benefitId, "limit_group_id", groupId));
    }

    private void performance(long card, String month, long amount) {
        insert("card_monthly_performance", "card_monthly_performance_id", Map.of("user_card_id", card,
                "performance_month", Date.valueOf(month), "recognized_amount", amount, "updated_at", now()));
    }

    private void usage(long card, long group, String month, long amount) {
        insert("monthly_limit_usage", "monthly_limit_usage_id", Map.of("user_card_id", card, "card_product_id", product,
                "limit_group_id", group, "usage_month", Date.valueOf(month), "used_value", amount, "updated_at", now()));
    }

    private void consumed(long card, long owner, long group, LocalDate day, long amount, boolean restored) {
        var payment = new HashMap<String, Object>(Map.of("user_id", owner, "user_card_id", card, "card_product_id", product,
                "merchant_id", merchant, "request_key", UUID.randomUUID().toString(), "request_fingerprint", new byte[32],
                "source", "SEED", "status", "APPROVED", "requested_amount", 10_000L, "requested_at", now()));
        payment.put("updated_at", now());
        long id = insert("payment_transaction", "payment_transaction_id", payment);
        var fields = new HashMap<String, Object>(Map.of("payment_transaction_id", id, "card_product_id", product,
                "limit_group_id", group, "remaining_before", 10_000L, "consumed_value", amount,
                "usage_date", day, "usage_month", day.withDayOfMonth(1), "updated_at", now()));
        if (restored) fields.put("restored_at", now());
        insert("payment_limit_usage", "payment_limit_usage_id", fields);
    }

    private Map<String, Object> snapshot() {
        var result = new LinkedHashMap<String, Object>();
        result.put("performance", jdbc.queryForList("select * from card_monthly_performance where user_card_id in (?, ?) order by card_monthly_performance_id", first, second));
        for (String table : List.of("monthly_limit_usage", "payment_limit_usage", "limit_group")) {
            result.put(table, jdbc.queryForList("select * from " + table + " where card_product_id = ? order by " + table + "_id", product));
        }
        return result;
    }

    private long insert(String table, String key, Map<String, Object> fields) {
        var values = new HashMap<>(fields);
        values.put("created_at", now());
        return new SimpleJdbcInsert(jdbc).withTableName(table).usingGeneratedKeyColumns(key)
                .usingColumns(values.keySet().toArray(String[]::new)).executeAndReturnKey(values).longValue();
    }

    private LocalDateTime now() { return LocalDateTime.now(ZoneOffset.UTC); }
    private PaymentAvailability available() { return new PaymentAvailability(Availability.AVAILABLE, null); }
    private RecommendationCommand command(Map<Long, PaymentAvailability> states) {
        return new RecommendationCommand(100, user, new Transaction(10_000, Plan.LUMP_SUM, 1,
                Instant.parse("2026-09-30T15:30:00Z"), merchant, "CAFE", Map.of()), states);
    }
}
