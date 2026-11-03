package com.yamu.be.payment.recommend;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.yamu.be.payment.recommend.RecommendationResult.*;
import com.yamu.be.card.domain.CardType;
import com.yamu.be.recommendation.PurchaseItemReader;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class RecommendationResultJsonTest {
    final RecommendationResultJson codec=new RecommendationResultJson(JsonMapper.builder().addModule(new JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build());
    Candidate candidate(List<BenefitLine> benefits,List<UnallocatedBenefitGroup> groups) {
        return new Candidate(1,new CardSummary(1L,10L,CardType.CHECK,false,null,"1234","KB","fixture"),1800,benefits,"fixture",
                SelectionReason.MAXIMUM_NET_BENEFIT,new RuleVersion(10L,1),groups);
    }
    RecommendationResult result(Candidate candidate) { return new RecommendationResult("rec_fixture",LocalDateTime.of(2026,11,3,0,0),false,List.of(candidate),List.of(),List.of()); }
    List<BenefitLine> lines(String timing) { return List.of(new BenefitLine(1L,"mobile","CASHBACK",java.math.BigDecimal.valueOf(1500),(Long)null,timing),
            new BenefitLine(2L,"subscription","CASHBACK",java.math.BigDecimal.valueOf(1000),(Long)null,timing),new BenefitLine(3L,"zero","CASHBACK",0,0,timing)); }
    UnallocatedBenefitGroup group() { return new UnallocatedBenefitGroup(List.of(1L,2L),9,1800,"KRW","SHARED_LIMIT_ALLOCATION_UNRESOLVED"); }

    @Test void roundTripPreservesKnownTotalNullAllocationAndConfirmedZero() {
        var original=result(candidate(lines("AFTER_BILLING"),List.of(group())));
        var json=codec.encode(original);
        assertThat(json).contains("\"schemaVersion\":1","\"amount\":null","\"amount\":0");
        assertThat(codec.decode(json)).isEqualTo(original);
        assertThat(codec.decode(json).status()).isEqualTo(Status.READY);
    }
    @Test void rejectsMissingVersionAndUnexplainedOrDuplicatedNulls() {
        assertThatThrownBy(()->codec.decode("{\"candidates\":[]}")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->candidate(lines("AFTER_BILLING"),List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->candidate(lines("AFTER_BILLING"),List.of(group(),group()))).isInstanceOf(IllegalArgumentException.class);
        var json=codec.encode(result(candidate(lines("AFTER_BILLING"),List.of(group()))));
        assertThatThrownBy(()->codec.decode(json.replace("\"unallocatedBenefitGroups\"","\"missingGroups\""))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void instantDiscountAddsUnallocatedTotalOnlyOnceAndCannotMixRealization() {
        assertThat(candidate(lines(BenefitLine.AT_APPROVAL),List.of(group())).instantDiscount()).isEqualTo(1800);
        assertThat(candidate(lines("AFTER_BILLING"),List.of(group())).instantDiscount()).isZero();
        var mixed=List.of(new BenefitLine(1L,"a","DISCOUNT",java.math.BigDecimal.valueOf(1500),(Long)null,BenefitLine.AT_APPROVAL),
                new BenefitLine(2L,"b","CASHBACK",java.math.BigDecimal.valueOf(1000),(Long)null,"AFTER_BILLING"));
        assertThatThrownBy(()->candidate(mixed,List.of(group())).instantDiscount()).isInstanceOf(IllegalStateException.class);
    }
    @Test void failedSaveDoesNotReturnAStoredResultAndScopesUpdateByOwnerAndPayment() {
        var jdbc=mock(JdbcTemplate.class);var items=mock(PurchaseItemReader.class);
        var store=new DatabaseRecommendationResultStore(jdbc,codec,items);
        var result=result(candidate(lines("AFTER_BILLING"),List.of(group())));
        assertThatThrownBy(()->store.save(7,100,result)).isInstanceOf(IllegalStateException.class);
        verify(jdbc).update("UPDATE payment_transaction SET recommendation_result_json=CAST(? AS JSON), version=version+1, updated_at=UTC_TIMESTAMP(6) WHERE payment_transaction_id=? AND user_id=?",
                codec.encode(result),100L,7L);
        verifyNoMoreInteractions(jdbc);verifyNoInteractions(items);
    }
}
