package com.yamu.be.assetflow;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yamu.be.account.AccountBalance;
import com.yamu.be.account.AccountBalanceService;
import com.yamu.be.cashflow.CashflowItem;
import com.yamu.be.cashflow.CashflowService;
import com.yamu.be.global.crypto.CipherField;
import com.yamu.be.global.crypto.PersonalDataCipher;
import com.yamu.be.global.finapi.FinApiException;
import com.yamu.be.payment.bank.*;
import com.yamu.be.support.AuthUsers;
import com.yamu.be.support.MySqlTestSupport;
import com.yamu.be.support.OwnershipFixtures;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;

/** 487~490을 함께 적용한 실제 Spring/인증/MySQL/Redis API 검증. 금융망 잔액·거래 응답만 대체한다. */
@AutoConfigureMockMvc
@Import(AssetFlowCrossFeatureIntegrationTest.ScenarioClock.class)
@TestPropertySource(properties="spring.application.name=yamu-cross-feature-test")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class AssetFlowCrossFeatureIntegrationTest extends MySqlTestSupport {
    static final Instant NOW=Instant.parse("2026-11-13T03:00:00Z");
    static final LocalDate TODAY=LocalDate.of(2026,11,13);
    static final LocalDateTime AS_OF=LocalDateTime.ofInstant(NOW.minusSeconds(120),ZoneOffset.UTC);
    @TestConfiguration static class ScenarioClock {
        @Bean @Primary Clock scenarioClock() { return Clock.fixed(NOW,ZoneOffset.UTC); }
    }
    @Container @ServiceConnection(name="redis")
    static final GenericContainer<?> REDIS=new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired PersonalDataCipher cipher;
    @Autowired CashflowService cashflows;
    @MockitoBean AccountBalanceService balances;
    @MockitoBean SsafyTransferApi bank;
    OwnershipFixtures fixtures;
    long owner,account,card,product,payment;

    @BeforeEach void setup() {
        fixtures=new OwnershipFixtures(jdbc);
        owner=fixtures.user(); account=account(owner,true);
        jdbc.update("UPDATE app_user SET forecast_settings=? WHERE user_id=?",
                "{\"horizon\":\"DAYS\",\"defaultDays\":30,\"alertThreshold\":1000}",owner);
        product=fixtures.product(); card=fixtures.card(owner,product,account,"ACTIVE");
        jdbc.update("UPDATE user_card SET payment_day=14 WHERE user_card_id=?",card);
        long merchant=insert("merchant","merchant_id",Map.of("merchant_name","통합 검증 카페","brand_code","CROSS-"+UUID.randomUUID(),
                "merchant_category_code","COFFEE_SHOP","created_at",AS_OF,"updated_at",AS_OF));
        var row=new LinkedHashMap<String,Object>();
        row.put("user_id",owner); row.put("user_card_id",card); row.put("card_product_id",product); row.put("merchant_id",merchant);
        row.put("request_key",UUID.randomUUID().toString()); row.put("request_fingerprint",new byte[32]);
        row.put("source","YAMU"); row.put("status","APPROVED"); row.put("requested_amount",10000);
        row.put("approved_amount",10000); row.put("installment_months",1); row.put("recognized_performance_amount",0);
        row.put("performance_month",TODAY.withDayOfMonth(1)); row.put("requested_at",AS_OF); row.put("approved_at",AS_OF);
        row.put("spending_class","EXCLUDED"); row.put("spending_class_source","USER"); row.put("created_at",AS_OF); row.put("updated_at",AS_OF);
        payment=insert("payment_transaction","payment_transaction_id",row);
        long benefit=insert("card_benefit","card_benefit_id",Map.of("card_product_id",product,"benefit_code","CROSS-"+UUID.randomUUID(),
                "policy_version",1,"benefit_name","할인","benefit_type","DISCOUNT","calculation_type","FIXED","fixed_value",100,
                "value_unit","KRW","application_timing","AT_APPROVAL","created_at",AS_OF));
        insert("payment_benefit","payment_benefit_id",Map.of("payment_transaction_id",payment,"card_product_id",product,
                "card_benefit_id",benefit,"benefit_value",100,"krw_amount",100,"value_unit","KRW","created_at",AS_OF));
        when(balances.getBalances(anyLong(),anyList(),anyBoolean())).thenAnswer(i->{
            List<Long> ids=i.getArgument(1); return ids.stream().map(id->new AccountBalance(id,3000,AS_OF)).toList();
        });
        when(bank.historySnapshot(anyString(),anyString(),any(),any(),isNull())).thenAnswer(i->snapshot(i.getArgument(2),i.getArgument(3)));
    }
    long account(long user,boolean primary) {
        long id=fixtures.account(user);
        jdbc.update("UPDATE linked_account SET account_no_enc=?,is_primary=? WHERE linked_account_id=?",
                cipher.encrypt("cross-account-"+id,CipherField.LINKED_ACCOUNT_NO),primary,id);
        jdbc.update("UPDATE app_user SET ssafy_member_id=?,ssafy_user_key=? WHERE user_id=?","cross-user-"+user,
                cipher.encrypt("cross-key-"+user,CipherField.APP_USER_SSAFY_USER_KEY),user);
        return id;
    }
    BankHistory snapshot(LocalDate from,LocalDate to) {
        return new BankHistory(from,to,null,1L,List.of(new BankTransaction("history-"+from,from,LocalTime.NOON,
                BankDirection.DEPOSIT,100,3000,"테스트")),NOW);
    }
    long insert(String table,String key,Map<String,Object> fields) {
        return new SimpleJdbcInsert(jdbc).withTableName(table).usingGeneratedKeyColumns(key)
                .usingColumns(fields.keySet().toArray(String[]::new)).executeAndReturnKey(fields).longValue();
    }
    JsonNode read(String path,String... params) throws Exception {
        var request=get(path).with(AuthUsers.asUser(owner));
        for(int i=0;i<params.length;i+=2) request.param(params[i],params[i+1]);
        return json.readTree(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }
    JsonNode flow(String month) throws Exception { return read("/api/asset-flows","yearMonth",month); }
    JsonNode at(JsonNode flow,LocalDate date) {
        for(JsonNode point:flow.path("series")) if(point.path("date").asText().equals(date.toString())) return point;
        throw new AssertionError("날짜 누락: "+date);
    }

    @Test void allFourFeaturesAgreeAcrossMonthAndPreserveActualCurrentForecastBoundary() throws Exception {
        cashflows.create(owner,new CashflowService.CreateCommand("예정 지출",CashflowItem.Direction.EXPENSE,2500L,TODAY.plusDays(5),
                CashflowItem.RecurrenceType.NONE,null,null,null,account,null,null,null));
        var home=read("/api/home");
        var report=read("/api/reports/monthly/benefits","yearMonth","2026-11","source","YAMU");
        assertThat(home.path("partialErrors")).isEmpty();
        assertThat(home.path("benefitSummary").path("totalBenefit")).isEqualTo(report.path("summary").path("expectedBenefit"));
        assertThat(home.path("benefitSummary").path("byType")).isEqualTo(report.path("summary").path("byType"));
        var november=flow("2026-11"); var december=flow("2026-12");
        assertThat(november.path("series")).hasSize(30);
        assertThat(december.path("series")).hasSize(31);
        assertThat(at(november,TODAY.minusDays(1)).path("kind").asText()).isEqualTo("ACTUAL");
        assertThat(at(november,TODAY.minusDays(1)).path("displayBalance").asLong()).isEqualTo(3000);
        assertThat(at(november,TODAY).path("kind").asText()).isEqualTo("CURRENT");
        assertThat(at(november,TODAY).path("displayBalance").asLong()).isEqualTo(3000);
        long min=Long.MAX_VALUE; LocalDate minDate=null;
        for(int d=1;d<=30;d++) {
            LocalDate date=TODAY.plusDays(d);
            var point=at(date.getMonthValue()==11?november:december,date);
            assertThat(point.path("kind").asText()).isEqualTo("FORECAST");
            long display=point.path("forecast").path("conservative").isNumber()
                    ?point.path("forecast").path("conservative").asLong():point.path("forecast").path("expected").asLong();
            assertThat(point.path("displayBalance").asLong()).isEqualTo(display);
            if(display<min) { min=display; minDate=date; }
        }
        var preview=home.path("forecastPreview");
        assertThat(preview.path("minBalance").asLong()).isEqualTo(min);
        assertThat(preview.path("minBalanceDate").asText()).isEqualTo(minDate.toString());
        assertThat(preview.path("nextRiskDate").asText()).isEqualTo(TODAY.plusDays(5).toString());
        assertThat(preview.path("balanceAsOf").asText()).isEqualTo(november.path("balanceAsOf").asText());
        assertThat(at(december,TODAY.plusDays(31)).path("dataStatus").asText()).isEqualTo("NO_DATA");
    }

    @Test void truncatedBankHistoryIsSplitAndReconstructedThroughActualApi() throws Exception {
        // 일별 조각마다 동일 잔고를 유지하는 0원 입금으로 연속성을 고정한다.
        doAnswer(i->{ LocalDate from=i.getArgument(2),to=i.getArgument(3);
            if(!from.equals(to)) return new BankHistory(from,to,null,100L,List.of(),NOW);
            return new BankHistory(from,to,null,1L,List.of(new BankTransaction("day-"+from,from,LocalTime.NOON,BankDirection.DEPOSIT,0,3000,"")),NOW);
        }).when(bank).historySnapshot(anyString(),anyString(),any(),any(),isNull());
        var response=flow("2026-11");
        assertThat(response.path("partialErrors")).isEmpty();
        assertThat(at(response,TODAY.minusDays(1)).path("displayBalance").asLong()).isEqualTo(3000);
        verify(bank,atLeast(2)).historySnapshot(anyString(),anyString(),any(),any(),isNull());
    }

    @Test void incompleteSecondAccountNeverProducesPartialTotal() throws Exception {
        long second=account(owner,false);
        doReturn(new BankHistory(TODAY.withDayOfMonth(1),TODAY.minusDays(1),null,null,List.of(),NOW)).when(bank)
                .historySnapshot(anyString(),eq("cross-account-"+second),any(),any(),isNull());
        var response=read("/api/asset-flows","yearMonth","2026-11","accountIds",account+","+second);
        var past=at(response,TODAY.minusDays(1));
        assertThat(past.path("displayBalance").isNull()).isTrue();
        assertThat(past.path("dataStatus").asText()).isEqualTo("NO_DATA");
        assertThat(at(response,TODAY).path("displayBalance").asLong()).isEqualTo(6000);
    }

    @Test void otherUsersAccountIsRejectedBeforeAnyFinanceCall() throws Exception {
        long other=account(fixtures.user(),false);
        mvc.perform(get("/api/asset-flows").param("accountIds",account+","+other).with(AuthUsers.asUser(owner)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        verifyNoInteractions(bank,balances);
    }

    @Test void historyTimeoutLeavesCurrentFutureAndHomeAvailable() throws Exception {
        doThrow(FinApiException.timedOut("history-test")).when(bank).historySnapshot(anyString(),anyString(),any(),any(),isNull());
        var response=flow("2026-11");
        assertThat(at(response,TODAY.minusDays(1)).path("dataStatus").asText()).isEqualTo("ERROR");
        assertThat(at(response,TODAY.minusDays(1)).path("displayBalance").isNull()).isTrue();
        assertThat(at(response,TODAY).path("dataStatus").asText()).isEqualTo("AVAILABLE");
        assertThat(at(response,TODAY.plusDays(1)).path("dataStatus").asText()).isEqualTo("AVAILABLE");
        assertThat(read("/api/home").path("forecastPreview").isObject()).isTrue();
    }

    @Test void rereadAfterCancellationAndScheduledExpenseDeletionUpdatesBothApis() throws Exception {
        var planned=cashflows.create(owner,new CashflowService.CreateCommand("예정",CashflowItem.Direction.EXPENSE,500L,TODAY.plusDays(4),
                CashflowItem.RecurrenceType.NONE,null,null,null,account,null,null,null));
        long before=read("/api/home").path("forecastPreview").path("minBalance").asLong();
        assertThat(at(flow("2026-11"),TODAY.plusDays(4)).path("displayBalance").asLong()).isEqualTo(before);
        jdbc.update("UPDATE payment_transaction SET status='CANCELED',canceled_at=? WHERE payment_transaction_id=?",AS_OF,payment);
        cashflows.delete(owner,planned.id(),CashflowService.DeleteScope.ALL,null);
        var home=read("/api/home"); var flow=flow("2026-11");
        assertThat(home.path("benefitSummary").path("totalBenefit").asLong()).isZero();
        assertThat(read("/api/reports/monthly/benefits","yearMonth","2026-11","source","YAMU").path("summary").path("expectedBenefit").asLong()).isZero();
        assertThat(home.path("forecastPreview").path("minBalance").asLong()).isEqualTo(before+500);
        assertThat(at(flow,TODAY.plusDays(4)).path("displayBalance").asLong()).isEqualTo(before+500);
    }

    @Test void allEndpointsRequireAuthentication() throws Exception {
        for(String path:List.of("/api/home","/api/asset-flows","/api/reports/monthly/benefits","/api/forecasts/balance"))
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
    }
}
