package com.yamu.be.seed.qa;

import static org.assertj.core.api.Assertions.*;
import com.yamu.be.payment.bank.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class QaLocalBankHistoryTest {
    private final Clock clock=Clock.fixed(Instant.parse("2026-11-17T04:00:00Z"),ZoneOffset.UTC);
    private final QaLocalBankHistory bank=new QaLocalBankHistory(List.of(
            new QaSeedSpec.Account("004","account-a"),new QaSeedSpec.Account("088","account-b"),
            new QaSeedSpec.Account("003","account-c")),clock);

    @Test void replaysIdenticalFundingForEachAccountAcrossOverlappingQueries() {
        for (int i=0;i<3;i++) {
            String account="account-"+(char)('a'+i);
            var day=bank.snapshot(account,QaLifeScenario.BASE,QaLifeScenario.BASE,null);
            var month=bank.snapshot(account,LocalDate.of(2026,11,1),LocalDate.of(2026,11,17),null);
            assertThat(day.complete()).isTrue();
            assertThat(month.transactions()).isEqualTo(day.transactions()).hasSize(1);
            assertThat(day.transactions().get(0).balanceAfter()).isEqualTo(QaLifeScenario.startingBalance(i));
            assertThat(bank.snapshot(account,QaLifeScenario.BASE,QaLifeScenario.BASE,null)).isEqualTo(day);
        }
    }

    @Test void returnsNoFundingOutsideItsDateOrForWithdrawals() {
        var october=bank.snapshot("account-a",LocalDate.of(2026,10,1),LocalDate.of(2026,10,31),null);
        var later=bank.snapshot("account-a",QaLifeScenario.BASE.plusDays(1),QaLifeScenario.BASE.plusDays(3),null);
        var withdrawals=bank.snapshot("account-a",QaLifeScenario.BASE,QaLifeScenario.BASE,BankDirection.WITHDRAWAL);
        for(var response:List.of(october,later,withdrawals)) {
            assertThat(response.complete()).isTrue();
            assertThat(response.transactions()).isEmpty();
            assertThat(response.reportedCount()).isZero();
        }
        assertThat(bank.snapshot("account-a",QaLifeScenario.BASE,QaLifeScenario.BASE,BankDirection.DEPOSIT).transactions()).hasSize(1);
    }

    @Test void rejectsUnknownAccountsAndInvalidPeriods() {
        assertThatThrownBy(()->bank.snapshot("other-account",QaLifeScenario.BASE,QaLifeScenario.BASE,null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->bank.snapshot("account-a",QaLifeScenario.BASE,QaLifeScenario.BASE.minusDays(1),null)).isInstanceOf(IllegalArgumentException.class);
    }
}
