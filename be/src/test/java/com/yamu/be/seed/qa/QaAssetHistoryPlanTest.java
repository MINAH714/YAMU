package com.yamu.be.seed.qa;
import java.time.LocalDate;
import java.util.List;
import com.yamu.be.payment.bank.BankDirection;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class QaAssetHistoryPlanTest {
    LocalDate day=LocalDate.of(2026,5,1);
    @Test void reconstructsToVerifiedBoundaryWithoutExtraTransactions() {
        var p=QaAssetHistoryPlan.build(List.of(
            new QaAssetHistoryPlan.Event("a",day,BankDirection.DEPOSIT,100),
            new QaAssetHistoryPlan.Event("b",day,BankDirection.WITHDRAWAL,200)),day,day,0);
        assertThat(p.opening()).isEqualTo(100);
        assertThat(p.closing()).isZero();
        assertThat(p.rows()).hasSize(2);
    }
    @Test void negativeIntermediateIsRejectedEvenWhenClosingMatches() {
        assertThatThrownBy(()->QaAssetHistoryPlan.build(List.of(
            new QaAssetHistoryPlan.Event("a",day,BankDirection.WITHDRAWAL,100),
            new QaAssetHistoryPlan.Event("b",day,BankDirection.DEPOSIT,100)),day,day,0))
            .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void duplicateAndNonpositiveEventsAreRejected() {
        var event=new QaAssetHistoryPlan.Event("a",day,BankDirection.DEPOSIT,10);
        assertThatThrownBy(()->QaAssetHistoryPlan.build(List.of(event,event),day,day,100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->QaAssetHistoryPlan.build(List.of(new QaAssetHistoryPlan.Event("a",day,BankDirection.DEPOSIT,0)),day,day,100))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
