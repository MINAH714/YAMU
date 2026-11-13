package com.yamu.be.assetflow;

import static org.assertj.core.api.Assertions.*;
import com.yamu.be.payment.bank.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class DailyBalanceReconstructorTest {
    private final DailyBalanceReconstructor calculator = new DailyBalanceReconstructor();
    private static final LocalDate FROM = LocalDate.of(2026, 11, 1);
    private static final LocalDate TO = FROM.plusDays(4);
    private static final Instant QUERIED = Instant.parse("2026-11-13T03:00:00Z");

    private BankTransaction row(String id, int day, int hour, BankDirection direction, long amount, long after) {
        return new BankTransaction(id, FROM.withDayOfMonth(day), LocalTime.of(hour, 0), direction, amount, after, "");
    }
    private BankHistory history(Long count, BankTransaction... rows) {
        return new BankHistory(FROM, TO, null, count, List.of(rows), QUERIED);
    }
    private void unavailable(BankHistory h, Long opening, DailyBalanceReconstructor.Reason reason) {
        var result = calculator.reconstruct(h, opening);
        assertThat(result.reason()).isEqualTo(reason);
        assertThat(result.points()).hasSize(5).allSatisfy(p -> assertThat(p.balance()).isNull());
    }

    @Test void derivesOpeningAndCarriesOnlyVerifiedNoTransactionDays() {
        var deposit=row("d",3,12,BankDirection.DEPOSIT,200,1100);
        var withdrawal=row("w",2,10,BankDirection.WITHDRAWAL,100,900);
        var result=calculator.reconstruct(history(2L,deposit,withdrawal),null);
        assertThat(result.reason()).isEqualTo(DailyBalanceReconstructor.Reason.OK);
        assertThat(result.points()).extracting(DailyBalanceReconstructor.Point::balance)
                .containsExactly(1000L,900L,1100L,1100L,1100L);
        assertThat(result.points()).extracting(DailyBalanceReconstructor.Point::date)
                .containsExactly(FROM,FROM.plusDays(1),FROM.plusDays(2),FROM.plusDays(3),TO);
    }
    @Test void usesLastTransactionOfDayWithoutSummingBalances() {
        var h=history(2L,row("a",1,9,BankDirection.DEPOSIT,200,1200),
                row("b",1,10,BankDirection.WITHDRAWAL,300,900));
        assertThat(calculator.reconstruct(h,1000L).points()).allSatisfy(p -> assertThat(p.balance()).isEqualTo(900L));
    }
    @Test void verifiedZeroOpeningIsActualZero() {
        assertThat(calculator.reconstruct(history(0L),0L).points()).allSatisfy(p -> assertThat(p.balance()).isZero());
    }
    @Test void emptyCompleteHistoryWithoutAnchorIsNotZero() {
        unavailable(history(0L),null,DailyBalanceReconstructor.Reason.NO_ANCHOR);
    }
    @Test void truncatedDayIsNotTreatedAsNoTransactions() {
        unavailable(history(2L,row("a",1,9,BankDirection.DEPOSIT,200,1200)),1000L,
                DailyBalanceReconstructor.Reason.HISTORY_INCOMPLETE);
    }
    @Test void missingCountCannotProveCoverage() {
        unavailable(history(null),1000L,DailyBalanceReconstructor.Reason.HISTORY_INCOMPLETE);
    }
    @Test void extraRowsBeyondCountAreAlsoInconsistent() {
        var h=history(0L,row("a",1,9,BankDirection.DEPOSIT,200,1200));
        assertThat(h.integrity()).isEqualTo(BankHistory.Integrity.COUNT_MISMATCH);
        unavailable(h,1000L,DailyBalanceReconstructor.Reason.HISTORY_INCOMPLETE);
    }
    @Test void duplicateIdentifiersDoNotProveCompleteHistory() {
        var a=row("a",1,9,BankDirection.DEPOSIT,200,1200);
        var h=history(2L,a,a);
        assertThat(h.integrity()).isEqualTo(BankHistory.Integrity.DUPLICATE_TRANSACTION);
        unavailable(h,1000L,DailyBalanceReconstructor.Reason.HISTORY_INCOMPLETE);
    }
    @Test void sameSecondOrderIsNotGuessedFromTransactionNumber() {
        unavailable(history(2L,row("999",1,9,BankDirection.DEPOSIT,200,1200),
                row("1",1,9,BankDirection.WITHDRAWAL,300,900)),1000L,
                DailyBalanceReconstructor.Reason.AMBIGUOUS_ORDER);
    }
    @Test void knownOpeningMustAgreeWithObservedBalances() {
        unavailable(history(1L,row("a",1,9,BankDirection.DEPOSIT,200,1200)),999L,
                DailyBalanceReconstructor.Reason.BALANCE_MISMATCH);
    }
    @Test void laterGapInBalanceChainInvalidatesWindow() {
        unavailable(history(2L,row("a",1,9,BankDirection.DEPOSIT,200,1200),
                row("b",3,10,BankDirection.WITHDRAWAL,300,800)),1000L,
                DailyBalanceReconstructor.Reason.BALANCE_MISMATCH);
    }
    @Test void depositOnlyOrWithdrawalOnlyCannotReconstructFullAccount() {
        var h=new BankHistory(FROM,TO,BankDirection.DEPOSIT,1L,
                List.of(row("a",1,9,BankDirection.DEPOSIT,200,1200)),QUERIED);
        unavailable(h,1000L,DailyBalanceReconstructor.Reason.FILTERED_HISTORY);
    }
    @Test void unexpectedDirectionIsNotAccepted() {
        var h=new BankHistory(FROM,TO,BankDirection.DEPOSIT,1L,
                List.of(row("a",1,9,BankDirection.WITHDRAWAL,200,800)),QUERIED);
        assertThat(h.integrity()).isEqualTo(BankHistory.Integrity.WRONG_DIRECTION);
    }
    @Test void outOfRangeTransactionIsNotSilentlyDropped() {
        var h=history(1L,row("a",6,9,BankDirection.DEPOSIT,200,1200));
        assertThat(h.integrity()).isEqualTo(BankHistory.Integrity.OUT_OF_RANGE);
        unavailable(h,1000L,DailyBalanceReconstructor.Reason.HISTORY_INCOMPLETE);
    }
    @Test void invalidRowIsNotUsed() {
        var h=history(1L,row("a",1,9,null,200,1200));
        assertThat(h.integrity()).isEqualTo(BankHistory.Integrity.INVALID_TRANSACTION);
    }
    @Test void openingInferenceOverflowIsUnknown() {
        unavailable(history(1L,row("a",1,9,BankDirection.WITHDRAWAL,Long.MAX_VALUE,1)),null,
                DailyBalanceReconstructor.Reason.ARITHMETIC_OVERFLOW);
    }
    @Test void todayIsNotPresentedAsObservedEndOfDay() {
        var h=new BankHistory(FROM,LocalDate.of(2026,11,13),null,0L,List.of(),QUERIED);
        assertThatThrownBy(() -> calculator.reconstruct(h,1000L)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void koreanMidnightDefinesPastBoundary() {
        var h=new BankHistory(FROM,LocalDate.of(2026,11,12),null,0L,List.of(),
                Instant.parse("2026-11-12T15:00:00Z"));
        assertThat(calculator.reconstruct(h,1000L).points()).hasSize(12);
    }
    @Test void immutableSnapshotAndResultsDoNotLeakBalancesInToString() {
        var rows=new ArrayList<BankTransaction>();rows.add(row("a",1,9,BankDirection.DEPOSIT,200,123456789));
        var h=new BankHistory(FROM,TO,null,1L,rows,QUERIED);rows.clear();
        assertThat(h.transactions()).hasSize(1);
        assertThatThrownBy(() -> h.transactions().clear()).isInstanceOf(UnsupportedOperationException.class);
        var result=calculator.reconstruct(h,null);
        assertThat(h.toString()).doesNotContain("123456789");
        assertThat(result.toString()).doesNotContain("123456789");
        assertThat(result.points().toString()).doesNotContain("123456789");
    }
    @Test void leapMonthIncludesEveryDate() {
        var h=new BankHistory(LocalDate.of(2024,2,1),LocalDate.of(2024,2,29),null,0L,List.of(),QUERIED);
        assertThat(calculator.reconstruct(h,1000L).points()).hasSize(29);
    }
}
