package com.yamu.be.recommendation;

import static org.assertj.core.api.Assertions.*;
import com.yamu.be.payment.PaymentPlan;
import com.yamu.be.payment.PaymentTerms;
import com.yamu.be.payment.recommend.*;
import com.yamu.be.support.MySqlTestSupport;
import com.yamu.be.support.OwnershipFixtures;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 실제 Seed/Repository/조립기를 통과한다. 실적·사용량·결제 가능 판정만 명시적 가상 고객 값이다. */
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties={"spring.application.name=simulation-seed-acceptance", "yamu.pos.terminals[0].brand-code=GS25",
        "spring.datasource.hikari.maximum-pool-size=1", "spring.datasource.hikari.connection-timeout=2000",
        "yamu.pos.terminals[0].pos-code=POS-TEST-CAFE", "yamu.pos.terminals[0].channel=OFFLINE", "yamu.pos.terminals[0].facility-type=GENERAL"})
@org.springframework.context.annotation.Import(SimulationSeedRecommendationDatabaseTest.TimeConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SimulationSeedRecommendationDatabaseTest extends MySqlTestSupport {
    @org.testcontainers.junit.jupiter.Container
    @org.springframework.boot.testcontainers.service.connection.ServiceConnection(name="redis")
    static final org.testcontainers.containers.GenericContainer<?> REDIS = new org.testcontainers.containers.GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    @org.springframework.boot.test.context.TestConfiguration
    static class TimeConfig {
        @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary
        java.time.Clock simulationClock() { return java.time.Clock.fixed(java.time.Instant.parse("2026-11-03T03:00:00Z"),java.time.ZoneOffset.UTC); }
    }
    @Autowired DataSource source;
    @Autowired JdbcTemplate jdbc;
    @Autowired CardRecommender port;
    @Autowired RecommendationResultJson codec;
    @Autowired DatabaseRecommendationResultStore stored;
    @Autowired PlatformTransactionManager transactions;
    @Autowired com.yamu.be.global.crypto.PersonalDataCipher cipher;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.yamu.be.account.SsafyAccountApi bank;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    com.yamu.be.payment.approve.CreditLimitSource creditLimits;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    com.yamu.be.payment.billing.CardBillRepository billReads;
    @Autowired com.yamu.be.account.AccountBalanceService accountBalances;
    @Autowired RecommendationAvailabilityReader availabilityReader;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    RecommendationInputAssembler inputAssembler;
    @Autowired com.yamu.be.payment.pos.PosPaymentRequestService pos;
    @Autowired com.yamu.be.payment.barcode.BarcodeStore barcodes;
    @Autowired com.yamu.be.payment.barcode.BarcodeService barcodeService;
    @Autowired RecommendationSnapshotStore snapshots;
    @Autowired com.yamu.be.payment.approve.PaymentApprovalService approvals;
    @Autowired com.yamu.be.payment.domain.PaymentTransactionRepository payments;
    @Autowired com.yamu.be.payment.request.PaymentRequestViewReader views;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper json;
    @org.junit.jupiter.api.BeforeEach void balances() {
        org.mockito.Mockito.when(bank.balance(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString())).thenReturn(1_000_000L);
    }
    @BeforeAll void seed() throws Exception {
        for(var name:List.of("01-card-catalog.sql","02-card-catalog-ab20.sql","03-benefit-composition.sql")) {
            try(var connection=source.getConnection()) { ScriptUtils.executeSqlScript(connection,new EncodedResource(new FileSystemResource(Path.of("seed",name)),StandardCharsets.UTF_8)); }
        }
    }
    long product(String name) { return jdbc.queryForObject("SELECT card_product_id FROM card_product WHERE product_name=?",Long.class,name); }
    long card(long user,long product,long previous,long preferredUsed) {
        var fixtures=new OwnershipFixtures(jdbc);long id=fixtures.card(user,product,fixtures.account(user),"ACTIVE");
        jdbc.update("UPDATE user_card SET payment_day=15 WHERE user_card_id=?",id);
        jdbc.update("UPDATE app_user SET ssafy_user_key=? WHERE user_id=?",cipher.encrypt("simulation-user",com.yamu.be.global.crypto.CipherField.APP_USER_SSAFY_USER_KEY),user);
        jdbc.update("UPDATE linked_account SET account_no_enc=? WHERE linked_account_id=(SELECT linked_account_id FROM user_card WHERE user_card_id=?)",cipher.encrypt("simulation-account-"+id,com.yamu.be.global.crypto.CipherField.LINKED_ACCOUNT_NO),id);
        jdbc.update("INSERT INTO card_monthly_performance(user_card_id,performance_month,recognized_amount,created_at,updated_at) VALUES(?,'2026-10-01',?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)),(?,'2026-11-01',0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",id,previous,id);
        jdbc.update("INSERT INTO monthly_limit_usage(user_card_id,card_product_id,limit_group_id,usage_month,used_value,created_at,updated_at) SELECT ?,card_product_id,limit_group_id,'2026-11-01',CASE WHEN group_code IN ('INTEGRATED','PREFERRED_MONTH') THEN ? ELSE 0 END,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) FROM limit_group WHERE card_product_id=? AND period_type='MONTHLY'",id,preferredUsed,product);
        return id;
    }
    RecommendationRequest request(long user,List<Long> cards,String brand,long amount,String item,Map<String,Boolean> facts) {
        var merchant=jdbc.queryForMap("SELECT merchant_id,merchant_category_code FROM merchant WHERE brand_code=?",brand);
        var availability=new HashMap<Long,RecommendationCommand.PaymentAvailability>();
        cards.forEach(id->availability.put(id,new RecommendationCommand.PaymentAvailability(RecommendationInput.Availability.AVAILABLE,null)));
        return new RecommendationRequest(100L,user,new PaymentTerms(amount,PaymentPlan.LUMP_SUM,null),
                new RecommendationRequest.MerchantContext(((Number)merchant.get("merchant_id")).longValue(),(String)merchant.get("merchant_category_code"),Set.of("APP_STORE", "COUPANG").contains(brand)?"ONLINE":"OFFLINE","GENERAL"),
                LocalDateTime.of(2026,11,3,3,0),item,availability,facts);
    }
    @ParameterizedTest(name = "전월 실적 {0}원 → 편의점 혜택 {1}원")
    @CsvSource({"299999,0", "300000,1000", "300001,1000"})
    void previousMonthSeedBoundaryUsesTransactionMonthAndDoesNotAddPurchase(long previous, long expected) {
        long user = new OwnershipFixtures(jdbc).user();
        long p = product("신한카드 Mr.Life");
        long id = card(user, p, previous, 0);
        // UTC로는 10월이지만 한국 거래일은 11월 1일이다. 10월 실적만 혜택 조건에 사용한다.
        jdbc.update("UPDATE card_monthly_performance SET recognized_amount=999999 WHERE user_card_id=? AND performance_month='2026-11-01'", id);
        var base = request(user, List.of(id), "GS25", 10_000, null, Map.of());
        var boundary = new RecommendationRequest(base.paymentId(), user, base.terms(), base.merchant(),
                LocalDateTime.of(2026, 10, 31, 15, 30), base.purchaseItemCode(), base.availability(), base.verifiedFacts());
        var before = boundarySnapshot(user, id);
        var result = port.recommend(boundary);
        assertThat(result.status()).isEqualTo(RecommendationResult.Status.READY);
        var candidate = result.topCandidate().orElseThrow();
        assertThat(candidate.netBenefit()).isEqualByComparingTo(Long.toString(expected));
        long convenience = jdbc.queryForObject("SELECT card_benefit_id FROM card_benefit WHERE card_product_id=? AND benefit_code='CONVENIENCE'", Long.class, p);
        assertThat(candidate.benefits()).filteredOn(b -> b.benefitId() == convenience).singleElement()
                .satisfies(b -> assertThat(b.amount()).isEqualTo(expected));
        assertThat(boundarySnapshot(user, id)).isEqualTo(before);
    }

    /** 서버 시계는 11월 3일로 유지한다. 한국 월 경계를 넘으면 전월 실적도 9월→10월로 바뀌어야 한다. */
    @ParameterizedTest(name = "UTC 거래 {0} → 전월 실적 {1}원")
    @CsvSource({"2026-10-31T14:30:00,299999", "2026-10-31T15:30:00,300000"})
    void koreanMonthBoundaryReadsSeptemberThenOctoberRegardlessOfServerClock(String utcTime, long expected) {
        long user = new OwnershipFixtures(jdbc).user();
        long p = product("신한카드 Mr.Life");
        long id = card(user, p, 300_000, 0);
        jdbc.update("INSERT INTO card_monthly_performance(user_card_id,performance_month,recognized_amount,created_at,updated_at) "
                + "VALUES(?,'2026-09-01',299999,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", id);
        jdbc.update("INSERT INTO monthly_limit_usage(user_card_id,card_product_id,limit_group_id,usage_month,used_value,created_at,updated_at) "
                + "SELECT ?,card_product_id,limit_group_id,'2026-10-01',0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) "
                + "FROM limit_group WHERE card_product_id=? AND period_type='MONTHLY'", id, p);
        var base = request(user, List.of(id), "GS25", 10_000, null, Map.of());
        var boundary = new RecommendationRequest(base.paymentId(), user, base.terms(), base.merchant(),
                LocalDateTime.parse(utcTime), base.purchaseItemCode(), base.availability(), base.verifiedFacts());
        var before = boundarySnapshot(user, id);
        var result = port.recommend(boundary);
        // 반환값을 stub하지 않는다. 실제 조회·조립을 실행하고 조립기에 전달된 전월 실적을 검사한다.
        var transactionDate = LocalDateTime.parse(utcTime).atOffset(java.time.ZoneOffset.UTC)
                .atZoneSameInstant(java.time.ZoneId.of("Asia/Seoul")).toLocalDate();
        org.mockito.Mockito.verify(inputAssembler).policy(
                org.mockito.ArgumentMatchers.argThat(c -> c.getId().equals(id)),
                org.mockito.ArgumentMatchers.any(RecommendationPolicyData.class),
                org.mockito.ArgumentMatchers.eq(transactionDate), org.mockito.ArgumentMatchers.eq(expected),
                org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any(RecommendationInput.Transaction.class),
                org.mockito.ArgumentMatchers.any(com.yamu.be.card.domain.CardProduct.class));
        if (transactionDate.getMonthValue() == 10) {
            // 모의 정책은 11월 한정이므로, 10월의 최종 할인액을 임의로 0원으로 확정하지 않는다.
            assertThat(result.status()).isEqualTo(RecommendationResult.Status.DATA_INSUFFICIENT);
            assertThat(result.issues()).extracting(RecommendationDataIssue::code).contains("POLICY_COMPLETENESS_UNVERIFIED");
        } else {
            assertThat(result.status()).isEqualTo(RecommendationResult.Status.READY);
            assertThat(result.topCandidate().orElseThrow().netBenefit()).isEqualByComparingTo("1000");
        }
        assertThat(boundarySnapshot(user, id)).isEqualTo(before);
    }

    @ParameterizedTest(name = "노리2 통합 한도 사용 {0}원 → 혜택 {1}원")
    @CsvSource({"17000,2500", "18200,1800", "20000,0"})
    void noriSharedLimitBoundaryReturnsExpectedConsumptionWithoutWriting(long used, long expected) {
        long user = new OwnershipFixtures(jdbc).user();
        long p = product("KB국민 노리2 체크카드(KB Pay)");
        long id = card(user, p, 200_000, used);
        var facts = Map.of("TRANSACTION_KIND:GIFT_CERTIFICATE_PURCHASE", false, "TRANSACTION_KIND:PREPAID_TOPUP", false,
                "TRANSACTION_KIND:PG_MERCHANT", false, "RULE:NON_PARTNER_EASY_PAY", false,
                "RULE:AGENCY_SITE", false, "PAYMENT_METHOD:KB_PAY", false);
        var before = boundarySnapshot(user, id);
        var result = port.recommend(request(user, List.of(id), "APP_STORE", 15_000, "YOUTUBE_PREMIUM_SUBSCRIPTION", facts));
        assertThat(result.status()).isEqualTo(RecommendationResult.Status.READY);
        var candidate = result.topCandidate().orElseThrow();
        assertThat(candidate.netBenefit()).isEqualByComparingTo(Long.toString(expected));
        long integrated = jdbc.queryForObject("SELECT limit_group_id FROM limit_group WHERE card_product_id=? AND group_code='INTEGRATED'", Long.class, p);
        assertThat(candidate.expectedLimitUses()).filteredOn(u -> u.limitGroupId() == integrated).singleElement().satisfies(u -> {
            assertThat(u.remainingBefore()).isEqualByComparingTo(Long.toString(20_000 - used));
            assertThat(u.consumed()).isEqualByComparingTo(Long.toString(expected));
            assertThat(u.remainingBefore().subtract(u.consumed())).isEqualByComparingTo(Long.toString(20_000 - used - expected));
        });
        assertThat(boundarySnapshot(user, id)).isEqualTo(before);
    }

    /** 합성 반올림 설정: 실제 Mr.Life 약관 검증이 아니라 DB→조립→추천 전달 검증이다. */
    @ParameterizedTest(name = "합성 {0}, 단위 {1}, 구매 {2}원 → 혜택 {3}원")
    @CsvSource({"HALF_UP,1,104,10", "HALF_UP,1,105,11", "HALF_UP,1,106,11",
            "DOWN,1,109,10", "DOWN,1,110,11", "DOWN,1,111,11",
            "HALF_UP,10,1049,100", "HALF_UP,10,1050,110", "HALF_UP,10,1051,110",
            "DOWN,10,1099,100", "DOWN,10,1100,110", "DOWN,10,1101,110"})
    void syntheticStoredRoundingBoundaryFlowsThroughRealRecommendation(String mode, int unit, long amount, long expected) {
        assertStoredRounding(mode, unit, amount, expected, null);
    }

    @Test void syntheticRemaining106IsLimitedBeforeRoundingToTenWithoutWriting() {
        // 119.9 → 한도 106 → 10원 단위 절사 100. 반대 순서라면 110 → 106이므로 결과가 다르다.
        assertStoredRounding("DOWN", 10, 1199, 100, 106L);
    }

    private void assertStoredRounding(String mode, int unit, long amount, long expected, Long remaining) {
        long user = new OwnershipFixtures(jdbc).user();
        long p = product("신한카드 Mr.Life");
        long id = card(user, p, 300_000, 0);
        long benefit = jdbc.queryForObject("SELECT card_benefit_id FROM card_benefit WHERE card_product_id=? AND benefit_code='CONVENIENCE'", Long.class, p);
        var original = jdbc.queryForMap("SELECT rounding_mode,rounding_unit,rounding_stage FROM card_benefit WHERE card_benefit_id=?", benefit);
        long group = jdbc.queryForObject("SELECT limit_group_id FROM limit_group WHERE card_product_id=? AND group_code='TIME_MONTHLY'", Long.class, p);
        if (remaining != null) jdbc.update("UPDATE monthly_limit_usage SET used_value=? WHERE user_card_id=? AND limit_group_id=?", 10_000 - remaining, id, group);
        try {
            jdbc.update("UPDATE card_benefit SET rounding_mode=?,rounding_unit=?,rounding_stage='PER_TRANSACTION' WHERE card_benefit_id=?", mode, unit, benefit);
            var before = boundarySnapshot(user, id);
            var result = port.recommend(request(user, List.of(id), "GS25", amount, null, Map.of()));
            assertThat(result.status()).isEqualTo(RecommendationResult.Status.READY);
            var candidate = result.topCandidate().orElseThrow();
            assertThat(candidate.netBenefit()).isEqualByComparingTo(Long.toString(expected));
            assertThat(candidate.benefits()).filteredOn(b -> b.benefitId() == benefit).singleElement()
                    .satisfies(b -> assertThat(b.amount()).isEqualTo(expected));
            assertThat(candidate.expectedLimitUses()).filteredOn(u -> u.limitGroupId() == group).singleElement().satisfies(u -> {
                assertThat(u.consumed()).isEqualByComparingTo(Long.toString(expected));
                if (remaining != null) assertThat(u.remainingBefore()).isEqualByComparingTo(remaining.toString());
            });
            assertThat(result.simulationRules()).anyMatch(r -> r.appliedFields().contains("calculationOrder=LIMIT_THEN_ROUND"));
            assertThat(boundarySnapshot(user, id)).isEqualTo(before);
        } finally {
            jdbc.update("UPDATE card_benefit SET rounding_mode=?,rounding_unit=?,rounding_stage=? WHERE card_benefit_id=?",
                    original.get("rounding_mode"), original.get("rounding_unit"), original.get("rounding_stage"), benefit);
        }
    }

    private Map<String, Object> boundarySnapshot(long user, long card) {
        return Map.of("performance", jdbc.queryForList("SELECT * FROM card_monthly_performance WHERE user_card_id=? ORDER BY performance_month", card),
                "usage", jdbc.queryForList("SELECT * FROM monthly_limit_usage WHERE user_card_id=? ORDER BY limit_group_id", card),
                "payments", jdbc.queryForList("SELECT * FROM payment_transaction WHERE user_id=? ORDER BY payment_transaction_id", user));
    }

    @Test void ordinarySeedPurchaseProducesPositiveRecommendationAndIsolatesUnknownProduct() {
        var fixtures=new OwnershipFixtures(jdbc);long user=fixtures.user();long p=product("신한카드 Mr.Life");long mr=card(user,p,400_000,0);
        long missing=fixtures.card(user,fixtures.product(),null,"ACTIVE");
        var before=jdbc.queryForList("SELECT * FROM monthly_limit_usage WHERE user_card_id=? ORDER BY limit_group_id",mr);
        var result=port.recommend(request(user,List.of(mr,missing),"GS25",10_000,null,Map.of()));
        assertThat(result.status()).isEqualTo(RecommendationResult.Status.PARTIAL_READY);
        assertThat(result.topCandidate().orElseThrow().card().userCardId()).isEqualTo(mr);
        assertThat(result.topCandidate().orElseThrow().netBenefit()).isEqualByComparingTo("1000");
        assertThat(result.simulationRules()).anyMatch(r->r.id().equals("YAMU_BASIC_PURCHASE"))
                .anyMatch(r->r.id().equals("YAMU_SIM_CARD_POLICY"));
        assertThat(result.uncomparedCards()).extracting(c->c.card().userCardId()).containsExactly(missing);
        assertThat(jdbc.queryForList("SELECT * FROM monthly_limit_usage WHERE user_card_id=? ORDER BY limit_group_id",mr)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM card_benefit WHERE card_product_id=? AND rounding_mode IS NOT NULL",Long.class,p)).isZero();
    }
    @Test void realNoriSeedPays1800WithoutAssigningIndividualAmountsAndPersistsSimulationRules() throws Exception {
        long user=new OwnershipFixtures(jdbc).user();long p=product("KB국민 노리2 체크카드(KB Pay)");long id=card(user,p,200_000,18_200);
        var facts=Map.of("TRANSACTION_KIND:GIFT_CERTIFICATE_PURCHASE",false,"TRANSACTION_KIND:PREPAID_TOPUP",false,
                "TRANSACTION_KIND:PG_MERCHANT",false,"RULE:NON_PARTNER_EASY_PAY",false,"RULE:AGENCY_SITE",false,"PAYMENT_METHOD:KB_PAY",false);
        var request=request(user,List.of(id),"APP_STORE",15_000,"YOUTUBE_PREMIUM_SUBSCRIPTION",facts);
        var result=port.recommend(request);
        assertThat(result.status()).isEqualTo(RecommendationResult.Status.READY);
        var candidate=result.topCandidate().orElseThrow();assertThat(candidate.netBenefit()).isEqualByComparingTo("1800");
        assertThat(candidate.unallocatedBenefitGroups()).singleElement().satisfies(g->assertThat(g.totalAmount()).isEqualTo(1800));
        assertThat(candidate.benefits()).filteredOn(b->candidate.unallocatedBenefitGroups().get(0).benefitIds().contains(b.benefitId()))
                .allSatisfy(b->assertThat(b.amount()).isNull());
        var restored=codec.decode(codec.encode(result));
        assertThat(restored).usingRecursiveComparison().ignoringFields("calculatedAt").isEqualTo(result);
        // 프로젝트 공통 LocalDateTime JSON 형식은 초 단위 UTC다.
        assertThat(restored.calculatedAt()).isEqualTo(result.calculatedAt().withNano(0));
        long merchant=request.merchant().merchantId();
        jdbc.update("INSERT INTO payment_transaction(user_id,merchant_id,request_key,request_fingerprint,source,status,requested_amount,requested_at,created_at,updated_at) VALUES(?,?,?,UNHEX(REPEAT('01',32)),'SEED','REQUESTED',15000,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",user,merchant,UUID.randomUUID().toString());
        long payment=jdbc.queryForObject("SELECT MAX(payment_transaction_id) FROM payment_transaction",Long.class);
        new TransactionTemplate(transactions).executeWithoutResult(s->stored.save(user,payment,result));
        // MySQL JSON 숫자 정규화로 소수 자릿수 표기는 달라질 수 있지만 값·NULL·추적 정보는 같아야 한다.
        assertThat(stored.find(user,payment).orElseThrow()).usingRecursiveComparison()
                .withComparatorForType(java.math.BigDecimal::compareTo,java.math.BigDecimal.class).isEqualTo(restored);
        snapshots.save(payment,result,java.time.Duration.ofMinutes(10));
        var displayed=views.find(payment,user).orElseThrow().recommendation().candidates().get(0);
        assertThat(displayed.netBenefit()).isEqualByComparingTo("1800");
        assertThat(displayed.unallocatedBenefitGroups()).singleElement().satisfies(g->assertThat(g.totalAmount()).isEqualTo(1800));
        assertThat(displayed.benefits()).filteredOn(b->candidate.unallocatedBenefitGroups().get(0).benefitIds().contains(b.benefitId()))
                .allSatisfy(b->assertThat(b.amount()).isNull());
        assertThat(json.writeValueAsString(displayed)).contains("\"amount\":null","\"totalAmount\":1800");
    }
    @Test void overseasWithoutCardFormRemainsUnknown() {
        long user=new OwnershipFixtures(jdbc).user();long id=card(user,product("현대카드 ZERO Edition3 (할인형)"),0,0);
        var result=port.recommend(request(user,List.of(id),"GS25",10_000,null,Map.of("REGION:OVERSEAS", true)));
        // 해외 거래에서는 국내외겸용 여부를 확인해야 한다.
        assertThat(result.status()).isEqualTo(RecommendationResult.Status.DATA_INSUFFICIENT);
        assertThat(result.candidates()).isEmpty();
    }

    @ParameterizedTest(name = "현대 M 구매 {0}원 → 기본 적립 {1}포인트")
    @CsvSource({"100000,1200", "100099,1201", "100100,1202", "100101,1202"})
    void realMSeedSplitsPrincipalAndKeepsKnownHalfUpRounding(long amount, long basePoints) {
        long user=new OwnershipFixtures(jdbc).user();long p=product("현대카드 M ME4");long id=card(user,p,1_000_000,9_000);
        // 이 음식점 거래에 관해 결제 담당이 확인해서 제공한 값이다. 모의 기본값을 확대하지 않는다.
        var facts=new HashMap<String,Boolean>();
        for(var kind:List.of("WATER_SEWAGE_BILL","STAMP_TAX","SERVICE_OF_PROCESS_FEE","PRIMARY_SECONDARY_SCHOOL_PAYMENT",
                "PRIVATE_KINDERGARTEN_EDUCATION_FEE","UNIVERSITY_TUITION","GRADUATE_TUITION","NATIONAL_PENSION",
                "HEALTH_INSURANCE","EMPLOYMENT_INSURANCE","INDUSTRIAL_ACCIDENT_INSURANCE","ELECTRICITY_BILL",
                "CITY_GAS_BILL","APARTMENT_MAINTENANCE","HIGHWAY_TOLL","POSTPAID_HIPASS","EXPRESS_BUS")) {
            facts.put("TRANSACTION_KIND:"+kind,false);
        }
        var merchant=jdbc.queryForMap("SELECT merchant_id,brand_code FROM merchant WHERE merchant_category_code='GENERAL_RESTAURANT' LIMIT 1");
        var before = boundarySnapshot(user, id);
        var result=port.recommend(request(user,List.of(id),(String)merchant.get("brand_code"),amount,null,facts));
        assertThat(result.status()).isEqualTo(RecommendationResult.Status.READY);
        var candidate=result.topCandidate().orElseThrow();
        assertThat(candidate.netBenefit()).isEqualByComparingTo(Long.toString(1000 + basePoints));
        assertThat(candidate.benefits()).extracting(RecommendationResult.BenefitLine::amount).contains(1_000L,basePoints);
        long baseBenefit = jdbc.queryForObject("SELECT card_benefit_id FROM card_benefit WHERE card_product_id=? AND benefit_code='BASE_15'", Long.class, p);
        assertThat(candidate.benefits()).filteredOn(b -> b.benefitId() == baseBenefit).singleElement().satisfies(b -> {
            assertThat(b.rawAmount()).isEqualByComparingTo(java.math.BigDecimal.valueOf(amount - 20_000).multiply(new java.math.BigDecimal("0.015")));
            assertThat(b.amount()).isEqualTo(basePoints);
        });
        assertThat(boundarySnapshot(user, id)).isEqualTo(before);
        assertThat(result.simulationRules()).anyMatch(r->r.appliedFields().contains("pointKrwRate=1(MOCK_COMPARISON_ONLY)"));
        assertThat(result.simulationRules()).noneMatch(r->r.appliedFields().contains("roundingMode=DOWN"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM card_benefit WHERE card_product_id=? AND rounding_mode='HALF_UP'",Long.class,p)).isEqualTo(3);
    }

    @Test void selfReferencingFallbackOnlyInvalidatesCardsWithThatPolicy() {
        var fixtures = new OwnershipFixtures(jdbc);
        long user = fixtures.user();
        long hyundaiProduct = product("현대카드 M ME4");
        long invalidCard = card(user, hyundaiProduct, 1_000_000, 0);
        long validCard = card(user, product("신한카드 Mr.Life"), 400_000, 0);
        var fallback = jdbc.queryForMap("""
                SELECT card_benefit_id, fallback_benefit_id FROM card_benefit
                WHERE card_product_id=? AND fallback_benefit_id IS NOT NULL LIMIT 1
                """, hyundaiProduct);
        long benefitId = ((Number) fallback.get("card_benefit_id")).longValue();
        try {
            assertThat(jdbc.update("UPDATE card_benefit SET fallback_benefit_id=card_benefit_id WHERE card_benefit_id=?",
                    benefitId)).isEqualTo(1);
            var result = port.recommend(request(user, List.of(invalidCard, validCard), "GS25", 10_000, null, Map.of()));
            assertThat(result.status()).isEqualTo(RecommendationResult.Status.PARTIAL_READY);
            assertThat(result.topCandidate().orElseThrow().card().userCardId()).isEqualTo(validCard);
            assertThat(result.uncomparedCards()).extracting(c -> c.card().userCardId()).containsExactly(invalidCard);
            assertThat(result.issues()).filteredOn(i -> i.code().equals("POLICY_DATA_INVALID"))
                    .singleElement().satisfies(i -> {
                        assertThat(i.userCardId()).isEqualTo(invalidCard);
                        assertThat(i.retryable()).isFalse();
                    });
        } finally {
            jdbc.update("UPDATE card_benefit SET fallback_benefit_id=? WHERE card_benefit_id=?",
                    fallback.get("fallback_benefit_id"), benefitId);
        }
    }

    @Test void approvedWindowDoesNotEnableDecember() {
        long user=new OwnershipFixtures(jdbc).user();long id=card(user,product("신한카드 Mr.Life"),400_000,0);
        var november=request(user,List.of(id),"GS25",10_000,null,Map.of());
        var december=new RecommendationRequest(november.paymentId(),user,november.terms(),november.merchant(),
                LocalDateTime.of(2026,12,3,3,0),null,november.availability(),Map.of());
        assertThat(port.recommend(december).candidates()).isEmpty();
        assertThat(port.recommend(december).simulationRules()).noneMatch(r->r.id().equals("YAMU_SIM_CARD_POLICY"));
    }

    @Test void unapprovedVersionAndExpiredSourcePoliciesAreNotRevived() {
        long user=new OwnershipFixtures(jdbc).user();long p=product("신한카드 Mr.Life");long id=card(user,p,400_000,0);
        var request=request(user,List.of(id),"GS25",10_000,null,Map.of());
        try {
            jdbc.update("UPDATE card_benefit SET policy_version=2 WHERE card_product_id=?",p);
            var future=port.recommend(request);
            assertThat(future.status()).isEqualTo(RecommendationResult.Status.DATA_INSUFFICIENT);
            assertThat(future.simulationRules()).noneMatch(r->r.id().equals("YAMU_SIM_CARD_POLICY"));
            jdbc.update("UPDATE card_benefit SET policy_version=1,valid_until='2026-11-02' WHERE card_product_id=?",p);
            var expired=port.recommend(request);
            assertThat(expired.status()).isEqualTo(RecommendationResult.Status.DATA_INSUFFICIENT);
            assertThat(expired.simulationRules()).noneMatch(r->r.id().equals("YAMU_SIM_CARD_POLICY"));
        } finally {
            jdbc.update("UPDATE card_benefit SET policy_version=1,valid_until=NULL WHERE card_product_id=?",p);
        }
    }

    @Test void fiveArgumentRequestResolvesServerAvailabilityAndDoesNotPretendSkippedLimitWasChecked() {
        long user=new OwnershipFixtures(jdbc).user();long id=card(user,product("신한카드 Mr.Life"),400_000,0);
        var base=request(user,List.of(id),"GS25",10_000,null,Map.of());
        var result=port.recommend(new RecommendationRequest(base.paymentId(),user,base.terms(),base.merchant(),base.requestedAt()));
        assertThat(result.status()).isEqualTo(RecommendationResult.Status.READY);
        assertThat(result.topCandidate().orElseThrow().netBenefit()).isEqualByComparingTo("1000");
        assertThat(result.issues()).anyMatch(i->Objects.equals(i.userCardId(),id)&&i.code().equals("CREDIT_LIMIT_CHECK_SKIPPED"));
        var forged=new RecommendationRequest(base.paymentId(),user,base.terms(),base.merchant(),base.requestedAt(),null,
                Map.of(id,new RecommendationCommand.PaymentAvailability(RecommendationInput.Availability.UNAVAILABLE,"INSUFFICIENT_BALANCE")),Map.of());
        assertThat(port.recommend(forged).status()).isEqualTo(RecommendationResult.Status.READY);
        org.mockito.Mockito.doReturn(com.yamu.be.payment.approve.CreditLimitSource.LimitCheck.exceeded(100L))
                .when(creditLimits).check(user,id,10_000);
        assertThat(port.recommend(base).excludedCards()).singleElement().satisfies(c->
                assertThat(c.reason()).isEqualTo(RecommendationResult.ExclusionReason.CREDIT_LIMIT_EXCEEDED));
        org.mockito.Mockito.doReturn(com.yamu.be.payment.approve.CreditLimitSource.LimitCheck.unknown())
                .when(creditLimits).check(user,id,10_000);
        assertThat(port.recommend(base).status()).isEqualTo(RecommendationResult.Status.DATA_INSUFFICIENT);
    }

    @Test void failedSharedCheckAccountDoesNotBlockCreditCardAndIsReadOnlyOnceWithoutHeldDbConnection() {
        long user=new OwnershipFixtures(jdbc).user();long mr=card(user,product("신한카드 Mr.Life"),400_000,0);
        long a=card(user,product("KB국민 노리2 체크카드(KB Pay)"),200_000,0);
        long b=card(user,product("KB국민 노리2 체크카드(KB Pay)"),200_000,0);
        long account=jdbc.queryForObject("SELECT linked_account_id FROM user_card WHERE user_card_id=?",Long.class,a);
        jdbc.update("UPDATE user_card SET linked_account_id=? WHERE user_card_id=?",account,b);
        var before=jdbc.queryForList("SELECT * FROM card_monthly_performance WHERE user_card_id IN (?,?,?) ORDER BY user_card_id,performance_month",mr,a,b);
        org.mockito.Mockito.when(bank.balance("simulation-user","simulation-account-"+a)).thenAnswer(inv->{
            assertNoHeldReadConnection();
            throw com.yamu.be.global.finapi.FinApiException.timedOut("balance");
        });
        var result=port.recommend(request(user,List.of(mr,a,b),"GS25",10_000,null,Map.of()));
        assertThat(result.status()).isEqualTo(RecommendationResult.Status.PARTIAL_READY);
        assertThat(result.topCandidate().orElseThrow().card().userCardId()).isEqualTo(mr);
        assertThat(result.uncomparedCards()).extracting(c->c.card().userCardId()).containsExactlyInAnyOrder(a,b);
        assertThat(result.issues()).filteredOn(i->i.code().equals("BALANCE_READ_RETRYABLE"))
                .extracting(RecommendationDataIssue::userCardId).containsExactlyInAnyOrder(a,b);
        org.mockito.Mockito.verify(bank,org.mockito.Mockito.times(1)).balance("simulation-user","simulation-account-"+a);
        assertThat(jdbc.queryForList("SELECT * FROM card_monthly_performance WHERE user_card_id IN (?,?,?) ORDER BY user_card_id,performance_month",mr,a,b)).isEqualTo(before);
    }

    @Test void databaseStatesExcludeInactiveExpiredInstallmentAndInsufficientCheckCardsAndRejectForeignAccess() {
        var fixtures=new OwnershipFixtures(jdbc);long user=fixtures.user();long p=product("KB국민 노리2 체크카드(KB Pay)");
        long inactive=card(user,p,200_000,0),expired=card(user,p,200_000,0),poor=card(user,p,200_000,0);
        jdbc.update("UPDATE user_card SET status='INACTIVE' WHERE user_card_id=?",inactive);
        jdbc.update("UPDATE user_card SET issuer_status='EXPIRED' WHERE user_card_id=?",expired);
        org.mockito.Mockito.when(bank.balance("simulation-user","simulation-account-"+poor)).thenReturn(1L);
        var base=request(user,List.of(inactive,expired,poor),"GS25",10_000,null,Map.of());
        var result=port.recommend(base);
        assertThat(result.status()).isEqualTo(RecommendationResult.Status.NO_ELIGIBLE_CARD);
        assertThat(result.excludedCards()).extracting(RecommendationResult.ExcludedCard::reason).containsExactlyInAnyOrder(
                RecommendationResult.ExclusionReason.CARD_NOT_ACTIVE,RecommendationResult.ExclusionReason.CARD_EXPIRED,
                RecommendationResult.ExclusionReason.INSUFFICIENT_BALANCE);
        var installment=new RecommendationRequest(base.paymentId(),user,PaymentTerms.installment(50_000,2),base.merchant(),base.requestedAt());
        assertThat(port.recommend(installment).excludedCards()).anyMatch(c->c.reason()==RecommendationResult.ExclusionReason.CHECK_CARD_INSTALLMENT_NOT_SUPPORTED);
        long foreign=fixtures.user(), foreignAccount=fixtures.account(foreign);
        var foreignResult=accountBalances.getBalanceResults(user,List.of(foreignAccount)).get(foreignAccount);
        assertThat(foreignResult.balance()).isNull();
        assertThat(foreignResult.errorCode()).isEqualTo(com.yamu.be.global.exception.ErrorCode.RESOURCE_NOT_FOUND);
        assertThatThrownBy(()->new TransactionTemplate(transactions).execute(s->port.recommend(base)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void unpaidCreditBillAndDisconnectedAccountAreConfirmedExclusions() {
        long user=new OwnershipFixtures(jdbc).user();long p=product("신한카드 Mr.Life");
        long unpaid=card(user,p,400_000,0), disconnected=card(user,p,400_000,0), normal=card(user,p,400_000,0);
        jdbc.update("INSERT INTO card_bill(user_id,user_card_id,billing_month,period_start,period_end,withdrawal_date,status,created_at,updated_at) VALUES(?,?,'2026-11-01','2026-10-02','2026-11-01','2026-11-15','UNPAID',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",user,unpaid);
        jdbc.update("UPDATE linked_account SET status='DISCONNECTED' WHERE linked_account_id=(SELECT linked_account_id FROM user_card WHERE user_card_id=?)",disconnected);
        var result=port.recommend(request(user,List.of(unpaid,disconnected,normal),"GS25",10_000,null,Map.of()));
        assertThat(result.topCandidate().orElseThrow().card().userCardId()).isEqualTo(normal);
        assertThat(result.excludedCards()).extracting(RecommendationResult.ExcludedCard::reason).containsExactlyInAnyOrder(
                RecommendationResult.ExclusionReason.UNPAID_BILL,RecommendationResult.ExclusionReason.PAYMENT_ACCOUNT_DISCONNECTED);
    }

    @Test void creditBillQueryFailureDoesNotInvalidateIndependentCheckCard() {
        long user=new OwnershipFixtures(jdbc).user();long mr=card(user,product("신한카드 Mr.Life"),400_000,0);
        long check=card(user,product("KB국민 노리2 체크카드(KB Pay)"),200_000,0);
        org.mockito.Mockito.doThrow(new org.springframework.dao.QueryTimeoutException("test timeout"))
                .when(billReads).findByUserCardIdInAndStatus(org.mockito.ArgumentMatchers.anyCollection(),org.mockito.ArgumentMatchers.eq(com.yamu.be.payment.billing.BillStatus.UNPAID));
        var facts=Map.of("PAYMENT_METHOD:KB_PAY",false,"RULE:AGENCY_SITE",false,"RULE:NON_PARTNER_EASY_PAY",false,"TRANSACTION_KIND:PG_MERCHANT",false);
        var result=port.recommend(request(user,List.of(mr,check),"GS25",10_000,null,facts));
        assertThat(result.status()).isEqualTo(RecommendationResult.Status.PARTIAL_READY);
        assertThat(result.topCandidate().orElseThrow().card().userCardId()).isEqualTo(check);
        assertThat(result.topCandidate().orElseThrow().netBenefit()).isEqualByComparingTo("500");
        assertThat(result.issues()).filteredOn(i->i.code().equals("DATABASE_READ_RETRYABLE"))
                .extracting(RecommendationDataIssue::userCardId).containsExactly(mr);
    }

    @Test void preparedRecommendationKeepsReadUsageUntilItsOnlyExecution() throws Exception {
        long user = new OwnershipFixtures(jdbc).user();
        long mr = card(user, product("신한카드 Mr.Life"), 400_000, 0);
        var request = request(user, List.of(mr), "GS25", 10_000, null, Map.of());
        var beforeSave = new RecommendationRequest(null, user, request.terms(), request.merchant(), request.requestedAt());

        var prepared = port.prepare(beforeSave);
        assertNoHeldReadConnection();
        jdbc.update("UPDATE monthly_limit_usage SET used_value=1000000 WHERE user_card_id=?", mr);

        // 한 연결뿐인 풀에서 저장 트랜잭션을 열고 실행한다. 추가 REQUIRES_NEW 조회는 연결을 얻지 못한다.
        var result = new TransactionTemplate(transactions).execute(status -> {
            long before = sessionQuestions();
            var calculated = prepared.apply(101L);
            // 두 번째 상태 조회 자체를 제외하면 같은 연결에서도 SQL이 없어야 한다.
            assertThat(sessionQuestions() - before).isEqualTo(1);
            return calculated;
        });
        assertThat(result.topCandidate().orElseThrow().netBenefit()).isEqualByComparingTo("1000");
        assertThatThrownBy(() -> prepared.apply(102L)).isInstanceOf(IllegalArgumentException.class);

        var next = port.prepare(beforeSave).apply(102L);
        assertThat(next.topCandidate().orElseThrow().netBenefit()).isEqualByComparingTo("0");
    }

    private long sessionQuestions() {
        return jdbc.queryForObject("SHOW SESSION STATUS LIKE 'Questions'", (rs, index) -> rs.getLong(2));
    }

    @Test void realPosAndApprovalSnapshotRegenerationUseServerStateAndBalanceOutsideTransactions() throws Exception {
        assertThat(source.unwrap(com.zaxxer.hikari.HikariDataSource.class).getMaximumPoolSize()).isEqualTo(1);
        long user=new OwnershipFixtures(jdbc).user();long mr=card(user,product("신한카드 Mr.Life"),400_000,0);
        long check=card(user,product("KB국민 노리2 체크카드(KB Pay)"),200_000,0);
        org.mockito.Mockito.when(bank.balance(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString())).thenAnswer(inv->{assertNoHeldReadConnection();return 100_000L;});
        var now=java.time.Instant.parse("2026-11-03T03:00:00Z");
        var token=String.format("%016d",7_000_000_000_000_000L+user);
        barcodes.issue(new com.yamu.be.payment.barcode.Barcode(token,user,com.yamu.be.payment.barcode.BarcodeStatus.ISSUED,now,now.plusSeconds(60)),java.time.Duration.ofMinutes(10));
        long merchant=jdbc.queryForObject("SELECT merchant_id FROM merchant WHERE brand_code='GS25'",Long.class);
        var body=json.readValue(json.writeValueAsString(Map.of("barcodeToken",token,"merchantId",merchant,"posCode","POS-TEST-CAFE","amount",10_000,"plan","LUMP_SUM")),com.yamu.be.payment.pos.PosPaymentRequest.class);
        String key=UUID.randomUUID().toString();
        var created=pos.create("test-pos-key",body,key);
        var initial=snapshots.find(created.paymentId()).orElseThrow();
        assertThat(initial.topCandidate().orElseThrow().card().userCardId()).isEqualTo(mr);
        assertThat(initial.topCandidate().orElseThrow().netBenefit()).isEqualByComparingTo("1000");
        var app=barcodeService.get(user,token).payment().recommendation();
        assertThat(app.candidates().get(0).userCardId()).isEqualTo(mr);
        assertThat(app.candidates().get(0).netBenefit()).isEqualByComparingTo("1000");
        assertThat(pos.create("test-pos-key",body,key).paymentId()).isEqualTo(created.paymentId());
        snapshots.delete(created.paymentId());
        long account=jdbc.queryForObject("SELECT linked_account_id FROM user_card WHERE user_card_id=?",Long.class,check);
        accountBalances.evict(account);
        var approved=approvals.approve(new com.yamu.be.global.security.AuthUser(user,1L),created.paymentId(),
                new com.yamu.be.payment.approve.PaymentApprovalRequest(null),UUID.randomUUID().toString());
        assertThat(approved.status()).isEqualTo(com.yamu.be.payment.domain.PaymentStatus.APPROVED);
        var regenerated=snapshots.find(created.paymentId()).orElseThrow();
        assertThat(regenerated.topCandidate().orElseThrow().netBenefit()).isEqualByComparingTo("1000");
        org.mockito.Mockito.verify(bank,org.mockito.Mockito.times(2)).balance("simulation-user","simulation-account-"+check);
    }

    private void assertNoHeldReadConnection() throws Exception {
        assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(source.unwrap(com.zaxxer.hikari.HikariDataSource.class).getHikariPoolMXBean().getActiveConnections()).isZero();
    }
}
