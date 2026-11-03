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
@TestPropertySource(properties={"spring.application.name=simulation-seed-acceptance", "yamu.pos.terminals[0].brand-code=GS25",
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
        assertThat(displayed.netBenefit()).isEqualTo(1800);
        assertThat(displayed.unallocatedBenefitGroups()).singleElement().satisfies(g->assertThat(g.totalAmount()).isEqualTo(1800));
        assertThat(displayed.benefits()).filteredOn(b->candidate.unallocatedBenefitGroups().get(0).benefitIds().contains(b.benefitId()))
                .allSatisfy(b->assertThat(b.amount()).isNull());
        assertThat(json.writeValueAsString(displayed)).contains("\"amount\":null","\"totalAmount\":1800");
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

    @Test void realPosAndApprovalSnapshotRegenerationUseServerStateAndBalanceOutsideTransactions() throws Exception {
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
        assertThat(app.candidates().get(0).netBenefit()).isEqualTo(1000);
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
