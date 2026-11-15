package com.yamu.be.report;

import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class BenefitBreakdownTest {
    private ReportReader.BenefitLine line(Long card, String type, Long amount, boolean confirmed) {
        return new ReportReader.BenefitLine(card,10L,"생활비 카드",null,type,BigDecimal.TEN,amount,confirmed);
    }
    @Test void confirmedIsSubsetAndCardAndTypeTotalsAgree() {
        var r=BenefitBreakdown.aggregate(List.of(line(1L,"DISCOUNT",1000L,true),
                line(1L,"CASHBACK",500L,false),line(2L,"POINT",200L,false),line(2L,"DISCOUNT",300L,true)));
        assertThat(r.byType()).extracting(BenefitReportResponse.TypeAmount::type).containsExactly("DISCOUNT","CASHBACK","POINT");
        assertThat(r.expectedBenefit()).isEqualTo(2000);
        assertThat(r.confirmedBenefit()).isEqualTo(1300);
        assertThat(r.byType().stream().mapToLong(BenefitReportResponse.TypeAmount::expectedBenefit).sum()).isEqualTo(2000);
        assertThat(r.cards().stream().mapToLong(BenefitReportResponse.CardBenefit::expectedBenefit).sum()).isEqualTo(2000);
        assertThat(r.cards()).extracting(BenefitReportResponse.CardBenefit::userCardId).containsExactly(1L,2L);
        assertThat(r.cards().get(0).expectedBenefit()).isEqualTo(1500);
        assertThat(r.cards().get(0).confirmedBenefit()).isEqualTo(1000);
        assertThat(r.cards().get(0).unconfirmedBenefit()).isEqualTo(500);
        assertThat(r.cards()).allSatisfy(c -> {
            assertThat(c.byType().stream().mapToLong(BenefitReportResponse.TypeAmount::expectedBenefit).sum()).isEqualTo(c.expectedBenefit());
            assertThat(c.confirmedBenefit()+c.unconfirmedBenefit()).isEqualTo(c.expectedBenefit());
        });
    }
    @Test void unconvertedPointsAreCountedWithoutAssumedCurrencyValue() {
        var r=BenefitBreakdown.aggregate(List.of(line(1L,"POINT",null,false),line(1L,"DISCOUNT",1000L,true)));
        assertThat(r.unconvertedBenefitCount()).isEqualTo(1);
        assertThat(r.byType().get(2).expectedBenefit()).isZero();
        assertThat(r.byType().get(2).unconvertedBenefitCount()).isEqualTo(1);
        assertThat(r.cards().get(0).expectedBenefit()).isEqualTo(1000);
        assertThat(r.cards().get(0).unconvertedBenefitCount()).isEqualTo(1);
    }
    @Test void zeroUnconvertedValueDoesNotSuggestMissingMoney() {
        var zero=new ReportReader.BenefitLine(1L,10L,"카드",null,"POINT",BigDecimal.ZERO,null,false);
        assertThat(BenefitBreakdown.aggregate(List.of(zero)).unconvertedBenefitCount()).isZero();
    }
    @Test void emptyMonthHasStableThreeTypesAndNoCards() {
        var r=BenefitBreakdown.aggregate(List.of());
        assertThat(r.cards()).isEmpty();
        assertThat(r.byType()).hasSize(3).allSatisfy(t->assertThat(t.expectedBenefit()).isZero());
    }
    @Test void approvedCardWithoutBenefitStillAppearsAndTieOrderIsStable() {
        var noBenefit=new ReportReader.BenefitLine(3L,10L,"카드",null,null,null,null,false);
        var r=BenefitBreakdown.aggregate(List.of(line(2L,"DISCOUNT",0L,false),noBenefit,line(1L,"POINT",0L,false)));
        assertThat(r.cards()).extracting(BenefitReportResponse.CardBenefit::userCardId).containsExactly(1L,2L,3L);
    }
    @Test void unknownCardGroupIsPreserved() {
        var r=BenefitBreakdown.aggregate(List.of(line(null,"DISCOUNT",100L,true),line(1L,"DISCOUNT",100L,true)));
        assertThat(r.cards()).extracting(BenefitReportResponse.CardBenefit::userCardId).containsExactly(1L,null);
        assertThat(r.cards().get(1).expectedBenefit()).isEqualTo(100);
    }
    @Test void sameProductWithDifferentCardsIsNotMerged() {
        var r=BenefitBreakdown.aggregate(List.of(line(1L,"DISCOUNT",200L,true),line(2L,"DISCOUNT",300L,true)));
        assertThat(r.cards()).hasSize(2).allSatisfy(c->assertThat(c.cardProductId()).isEqualTo(10));
    }
    @Test void overflowNeverWrapsIntoNegativeBenefit() {
        assertThatThrownBy(()->BenefitBreakdown.aggregate(List.of(line(1L,"DISCOUNT",Long.MAX_VALUE,true),
                line(2L,"DISCOUNT",1L,true)))).isInstanceOf(ArithmeticException.class);
    }
    @Test void unknownTypeIsNotSilentlyExcluded() {
        assertThatThrownBy(()->BenefitBreakdown.aggregate(List.of(line(1L,"UNKNOWN",100L,false))))
                .isInstanceOf(IllegalStateException.class);
    }
    @Test void overflowAcrossTypesAndCardsIsAlsoDetected() {
        assertThatThrownBy(()->BenefitBreakdown.aggregate(List.of(line(1L,"DISCOUNT",Long.MAX_VALUE,true),
                line(2L,"CASHBACK",1L,false)))).isInstanceOf(ArithmeticException.class);
    }
}
