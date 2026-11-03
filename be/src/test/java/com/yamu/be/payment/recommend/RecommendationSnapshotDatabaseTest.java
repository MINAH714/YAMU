package com.yamu.be.payment.recommend;

import static org.assertj.core.api.Assertions.*;
import com.yamu.be.support.MySqlTestSupport;
import com.yamu.be.support.OwnershipFixtures;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties="spring.application.name=recommendation-snapshot-database")
class RecommendationSnapshotDatabaseTest extends MySqlTestSupport {
    @Autowired JdbcTemplate jdbc;
    @Autowired RecommendationSnapshotStore store;
    @Autowired PlatformTransactionManager transactions;
    @Test void persistsOnlyOwnedPaymentAndReadsStoredSnapshotWithoutRecalculationOrUsageWrites() {
        var fixtures=new OwnershipFixtures(jdbc);long user=fixtures.user();long other=fixtures.user();
        jdbc.update("INSERT INTO merchant(merchant_name,brand_code,merchant_category_code,created_at,updated_at) VALUES('fixture',?,'CAFE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",UUID.randomUUID().toString());
        long merchant=jdbc.queryForObject("SELECT MAX(merchant_id) FROM merchant",Long.class);
        jdbc.update("INSERT INTO payment_transaction(user_id,merchant_id,request_key,request_fingerprint,source,status,requested_amount,requested_at,created_at,updated_at) VALUES(?,?,?,UNHEX(REPEAT('01',32)),'SEED','REQUESTED',10000,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",user,merchant,UUID.randomUUID().toString());
        long payment=jdbc.queryForObject("SELECT MAX(payment_transaction_id) FROM payment_transaction",Long.class);
        var tx=new TransactionTemplate(transactions);
        long product=fixtures.product();long card=fixtures.card(user,product,null,"ACTIVE");
        // 보존 형식 검증용 확정 총액 fixture다. 실제 상품 계산 완료를 주장하지 않는다.
        var summary=new RecommendationResult.CardSummary(card,product,com.yamu.be.card.domain.CardType.CREDIT,false,null,"1234","TEST","snapshot fixture");
        var lines=List.of(
                new RecommendationResult.BenefitLine(1L,"mobile","CASHBACK",java.math.BigDecimal.valueOf(1500),null,"AFTER_BILLING"),
                new RecommendationResult.BenefitLine(2L,"subscription","CASHBACK",java.math.BigDecimal.valueOf(1000),null,"AFTER_BILLING"),
                new RecommendationResult.BenefitLine(3L,"confirmed zero","CASHBACK",0,0,"AFTER_BILLING"));
        var group=new RecommendationResult.UnallocatedBenefitGroup(List.of(1L,2L),9,1800,"KRW","SHARED_LIMIT_ALLOCATION_UNRESOLVED");
        var candidate=new RecommendationResult.Candidate(1,summary,1800,lines,"fixture",
                RecommendationResult.SelectionReason.MAXIMUM_NET_BENEFIT,new RecommendationResult.RuleVersion(product,1),List.of(group));
        var result=new RecommendationResult("rec_snapshot",LocalDateTime.of(2026,11,3,0,0),false,List.of(candidate),List.of(),List.of());
        assertThat(store.find(user,payment)).isEmpty();
        assertThatThrownBy(()->tx.executeWithoutResult(s->store.save(other,payment,result)))
                .isInstanceOf(org.springframework.dao.InvalidDataAccessApiUsageException.class)
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasMessage("추천 결과 저장 대상 없음");
        tx.executeWithoutResult(s->store.save(user,payment,result));
        assertThat(store.find(user,payment)).contains(result);assertThat(store.find(other,payment)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT JSON_TYPE(JSON_EXTRACT(recommendation_result_json,'$.candidates[0].benefits[0].amount')) FROM payment_transaction WHERE payment_transaction_id=?",String.class,payment)).isEqualTo("NULL");
        assertThat(jdbc.queryForObject("SELECT JSON_EXTRACT(recommendation_result_json,'$.candidates[0].benefits[2].amount') FROM payment_transaction WHERE payment_transaction_id=?",String.class,payment)).isEqualTo("0");
        var before=jdbc.queryForMap("SELECT status,version,approved_at,purchase_item_code FROM payment_transaction WHERE payment_transaction_id=?",payment);
        store.find(user,payment);store.find(user,payment);
        assertThat(jdbc.queryForMap("SELECT status,version,approved_at,purchase_item_code FROM payment_transaction WHERE payment_transaction_id=?",payment)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_limit_usage",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM card_monthly_performance",Long.class)).isZero();
    }
}
