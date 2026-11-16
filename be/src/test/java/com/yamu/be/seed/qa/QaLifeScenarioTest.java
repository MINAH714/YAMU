package com.yamu.be.seed.qa;

import static org.assertj.core.api.Assertions.*;
import com.yamu.be.payment.bank.BankDirection;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class QaLifeScenarioTest {
    @Test void everyAccountHasNonnegativeChronologicalLedgerAndExactStartingBalance() {
        for(int account=0;account<3;account++) {
            var plan=QaLifeScenario.ledger(account);
            assertThat(plan.opening()).isNotNegative();
            long balance=plan.opening();
            for(var row:plan.rows()) {
                balance+=row.direction()==BankDirection.DEPOSIT?row.amount():-row.amount();
                assertThat(balance).as("account %d at %s %s",account,row.date(),row.bankTransactionNo()).isNotNegative().isEqualTo(row.balanceAfter());
            }
            assertThat(balance).isEqualTo(QaLifeScenario.startingBalance(account));
            System.out.printf("account=%d opening=%d in=%d out=%d closing=%d%n",account,plan.opening(),plan.deposits(),plan.withdrawals(),plan.closing());
        }
    }
    @Test void transfersHaveTwoOppositeEntriesAndDoNotChangeTotalAssets() {
        var pairs=new HashMap<String,List<QaAssetHistoryPlan.Event>>();
        for(int a=0;a<3;a++) for(var e:QaLifeScenario.events(a)) if(e.id().contains("TRANSFER"))
            pairs.computeIfAbsent(e.id(),k->new ArrayList<>()).add(e);
        assertThat(pairs).hasSize(22);
        for(var pair:pairs.values()) {
            assertThat(pair).hasSize(2);
            assertThat(pair.get(0).direction()).isNotEqualTo(pair.get(1).direction());
            assertThat(pair.get(0).amount()).isEqualTo(pair.get(1).amount());
            assertThat(pair.get(0).date()).isEqualTo(pair.get(1).date());
        }
    }
    @Test void decemberContainsOnlyPreparedNovemberPurchasesAndJulyApplianceIsLumpSum() {
        assertThat(QaLifeScenario.bills().stream().filter(b->b.month().equals(YearMonth.of(2026,12))).mapToLong(QaLifeScenario.Bill::amount).sum()).isEqualTo(420_000);
        assertThat(QaLifeScenario.bills().stream().filter(b->b.month().equals(YearMonth.of(2026,11))))
                .allMatch(b->b.due().equals(QaLifeScenario.BASE));
        assertThat(QaLifeScenario.payments(QaLifeScenario.BASE)).filteredOn(p->p.amount()==900_000)
                .singleElement().satisfies(p->{assertThat(p.date()).isEqualTo(LocalDate.of(2026,7,11));assertThat(p.installments()).isEqualTo(1);});
        assertThat(QaLifeScenario.payments(QaLifeScenario.BASE)).allMatch(p->p.date().isBefore(QaLifeScenario.BASE));
    }
    @Test void hasNoSpendDaysAndNoHardCodedUtilityDiscount() {
        var days=QaLifeScenario.payments(QaLifeScenario.BASE).stream().map(QaHistoryPlan.Payment::date).collect(java.util.stream.Collectors.toSet());
        assertThat(days).doesNotContain(LocalDate.of(2026,11,2),LocalDate.of(2026,11,16));
        assertThat(QaLifeScenario.payments(QaLifeScenario.BASE)).allMatch(p->p.utilityDiscount()==0);
    }
    /** Captured from the pre-extension seed, including every transaction's post-balance. */
    @Test void benefitReplayPreservesExistingGrossPurchases() throws Exception {
        var expected=new Properties();
        try(var input=getClass().getResourceAsStream("/qa/life-pre-extension.properties")) { expected.load(input); }
        assertThat(digest(QaLifeScenario.payments(QaLifeScenario.BASE).stream()
            .filter(p->!p.date().isBefore(LocalDate.of(2026,3,1)))
            // The approved revision changes this merchant only; every other purchase fact remains identical.
            .map(p->p.card()==3 && p.date().equals(LocalDate.of(2026,11,8))
                ? new QaHistoryPlan.Payment(p.card(),p.date(),p.slot(),"YAMU_TEST_RESTAURANT",p.amount(),p.status(),p.installments(),p.utilityDiscount()) : p)
            .toList().toString())).isEqualTo(expected.getProperty("payments"));
        assertThat(QaLifeScenario.payments(QaLifeScenario.BASE).stream().filter(p->QaLifeBenefits.approved(p)<p.amount()).toList())
            .singleElement().satisfies(p->{
                assertThat(p.brand()).isEqualTo("GS25");
                assertThat(p.amount()).isEqualTo(22000);
                assertThat(QaLifeBenefits.approved(p)).isEqualTo(19800);
            });
    }
    @Test void addedMonthsHaveSalaryRentCardBillsAndPairedBudgets() {
        assertThat(QaLifeScenario.FROM).isEqualTo(LocalDate.of(2025,12,1));
        for(var month=YearMonth.of(2025,12);month.isBefore(YearMonth.of(2026,4));month=month.plusMonths(1)) {
            final var ym=month;
            var bank=QaLifeScenario.events(0).stream().filter(e->YearMonth.from(e.date()).equals(ym)).toList();
            assertThat(bank).filteredOn(e->e.id().startsWith("00-SALARY")).singleElement()
                .satisfies(e->assertThat(e.amount()).isEqualTo(2_500_000));
            assertThat(bank).filteredOn(e->e.id().startsWith("10-RENT")).singleElement()
                .satisfies(e->assertThat(e.amount()).isEqualTo(700_000));
            assertThat(QaLifeScenario.bills()).filteredOn(b->b.month().equals(ym)).hasSize(2);
        }
        assertThat(QaLifeScenario.bills()).filteredOn(b->b.month().equals(YearMonth.of(2026,2)))
            .allMatch(b->b.due().equals(LocalDate.of(2026,2,19)));
        assertThat(QaLifeScenario.bills()).filteredOn(b->b.month().equals(YearMonth.of(2026,3)))
            .allMatch(b->b.due().equals(LocalDate.of(2026,3,16)));
    }
    private static String digest(String text) throws Exception {
        return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
            .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }}
