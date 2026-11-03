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
@TestPropertySource(properties="spring.application.name=simulation-seed-acceptance")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SimulationSeedRecommendationDatabaseTest extends MySqlTestSupport {
    @Autowired DataSource source;
    @Autowired JdbcTemplate jdbc;
    @Autowired CardRecommender port;
    @Autowired RecommendationResultJson codec;
    @Autowired DatabaseRecommendationResultStore stored;
    @Autowired PlatformTransactionManager transactions;
    @BeforeAll void seed() throws Exception {
        for(var name:List.of("01-card-catalog.sql","02-card-catalog-ab20.sql","03-benefit-composition.sql")) {
            try(var connection=source.getConnection()) { ScriptUtils.executeSqlScript(connection,new EncodedResource(new FileSystemResource(Path.of("seed",name)),StandardCharsets.UTF_8)); }
        }
    }
    long product(String name) { return jdbc.queryForObject("SELECT card_product_id FROM card_product WHERE product_name=?",Long.class,name); }
    long card(long user,long product,long previous,long preferredUsed) {
        var fixtures=new OwnershipFixtures(jdbc);long id=fixtures.card(user,product,fixtures.account(user),"ACTIVE");
        jdbc.update("INSERT INTO card_monthly_performance(user_card_id,performance_month,recognized_amount,created_at,updated_at) VALUES(?,'2026-10-01',?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)),(?,'2026-11-01',0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",id,previous,id);
        jdbc.update("INSERT INTO monthly_limit_usage(user_card_id,card_product_id,limit_group_id,usage_month,used_value,created_at,updated_at) SELECT ?,card_product_id,limit_group_id,'2026-11-01',CASE WHEN group_code IN ('INTEGRATED','PREFERRED_MONTH') THEN ? ELSE 0 END,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) FROM limit_group WHERE card_product_id=? AND period_type='MONTHLY'",id,preferredUsed,product);
        return id;
    }
    RecommendationRequest request(long user,List<Long> cards,String brand,long amount,String item,Map<String,Boolean> facts) {
        var merchant=jdbc.queryForMap("SELECT merchant_id,merchant_category_code FROM merchant WHERE brand_code=?",brand);
        var availability=new HashMap<Long,RecommendationCommand.PaymentAvailability>();
        cards.forEach(id->availability.put(id,new RecommendationCommand.PaymentAvailability(RecommendationInput.Availability.AVAILABLE,null)));
        return new RecommendationRequest(100L,user,new PaymentTerms(amount,PaymentPlan.LUMP_SUM,null),
                new RecommendationRequest.MerchantContext(((Number)merchant.get("merchant_id")).longValue(),(String)merchant.get("merchant_category_code"),brand.equals("APP_STORE")?"ONLINE":"OFFLINE","GENERAL"),
                LocalDateTime.of(2026,11,3,3,0),item,availability,facts);
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
    @Test void realNoriSeedPays1800WithoutAssigningIndividualAmountsAndPersistsSimulationRules() {
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
    }
    @Test void explicitOutsideScopeFactRemainsUnknownAndCannotTurnAllPoliciesComplete() {
        long user=new OwnershipFixtures(jdbc).user();long id=card(user,product("현대카드 ZERO Edition3 (할인형)"),0,0);
        var result=port.recommend(request(user,List.of(id),"GS25",10_000,null,Map.of()));
        // 국내 모의라는 이유만으로 별도 RULE:OVERSEAS_ELIGIBILITY를 충족시켜서는 안 된다.
        assertThat(result.status()).isEqualTo(RecommendationResult.Status.DATA_INSUFFICIENT);
        assertThat(result.candidates()).isEmpty();
    }

    @Test void realMSeedSplitsPrincipalAndKeepsKnownHalfUpRounding() {
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
        var result=port.recommend(request(user,List.of(id),(String)merchant.get("brand_code"),100_000,null,facts));
        assertThat(result.status()).isEqualTo(RecommendationResult.Status.READY);
        var candidate=result.topCandidate().orElseThrow();
        assertThat(candidate.netBenefit()).isEqualByComparingTo("2200");
        assertThat(candidate.benefits()).extracting(RecommendationResult.BenefitLine::amount).contains(1_000L,1_200L);
        assertThat(result.simulationRules()).anyMatch(r->r.appliedFields().contains("pointKrwRate=1(MOCK_COMPARISON_ONLY)"));
        assertThat(result.simulationRules()).noneMatch(r->r.appliedFields().contains("roundingMode=DOWN"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM card_benefit WHERE card_product_id=? AND rounding_mode='HALF_UP'",Long.class,p)).isEqualTo(3);
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
}
