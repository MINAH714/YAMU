package com.yamu.be.seed.qa;

import com.yamu.be.payment.bank.BankDirection;
import java.time.*;
import java.util.*;

/** Offline arithmetic report. No input secrets, database, server or financial client. */
public final class QaLifePlanCli {
    public static void main(String[] args) {
        boolean valid=true;
        System.out.println("Month,KB check,IBK check,First credit,MrLife credit,Card total,Rent,Living total");
        for(var ym=YearMonth.from(QaLifeScenario.FROM);!ym.isAfter(YearMonth.from(QaLifeScenario.BASE));ym=ym.plusMonths(1)) { final var month=ym;
            long[] totals=new long[4];
            QaLifeScenario.payments(QaLifeScenario.BASE).stream().filter(p->YearMonth.from(p.date()).equals(month)).forEach(p->totals[p.card()]+=p.amount());
            long sum=Arrays.stream(totals).sum(),rent=month.isBefore(YearMonth.from(QaLifeScenario.BASE))?700000:0;
            System.out.printf("%s,%d,%d,%d,%d,%d,%d,%d%n",month,totals[0],totals[3],totals[1],totals[2],sum,rent,sum+rent);
        }
        System.out.println("Account,Opening,Deposits,Withdrawals,Closing,Minimum");
        for(int a=0;a<3;a++) {
            var events=QaLifeScenario.events(a).stream().sorted(Comparator.comparing(QaAssetHistoryPlan.Event::date).thenComparing(QaAssetHistoryPlan.Event::id)).toList();
            long in=events.stream().filter(e->e.direction()==BankDirection.DEPOSIT).mapToLong(QaAssetHistoryPlan.Event::amount).sum();
            long out=events.stream().filter(e->e.direction()==BankDirection.WITHDRAWAL).mapToLong(QaAssetHistoryPlan.Event::amount).sum();
            long opening=QaLifeScenario.startingBalance(a)-in+out,balance=opening,min=opening;
            for(var e:events) {balance+=e.direction()==BankDirection.DEPOSIT?e.amount():-e.amount(); min=Math.min(min,balance);}
            System.out.printf("%d,%d,%d,%d,%d,%d%n",a,opening,in,out,balance,min);
            if(min<0) valid=false;
        }
        System.out.println("Card,Billing month,Withdrawal date,Amount,Initial status");
        for(var b:QaLifeScenario.bills()) System.out.printf("%d,%s,%s,%d,%s%n",b.card(),b.month(),b.due(),b.amount(),b.due().isBefore(QaLifeScenario.BASE)?"PAID":b.month().getMonthValue()==11?"CONFIRMED":"ACCUMULATING");
        if(!valid) throw new IllegalStateException("Negative ledger: do not apply");
        System.out.println("Account,Month,Opening,Deposits,Withdrawals,Closing");
        for(int a=0;a<3;a++) {
            var ledger=QaLifeScenario.ledger(a);long balance=ledger.opening();
            for(var ym=YearMonth.from(QaLifeScenario.FROM);!ym.isAfter(YearMonth.from(QaLifeScenario.BASE));ym=ym.plusMonths(1)) { final var month=ym;long opening=balance;
                long in=ledger.rows().stream().filter(r->YearMonth.from(r.date()).equals(month)&&r.direction()==BankDirection.DEPOSIT).mapToLong(r->r.amount()).sum();
                long out=ledger.rows().stream().filter(r->YearMonth.from(r.date()).equals(month)&&r.direction()==BankDirection.WITHDRAWAL).mapToLong(r->r.amount()).sum();
                balance+=in-out;
                System.out.printf("%d,%s,%d,%d,%d,%d%n",a,month,opening,in,out,balance);
            }
        }
    }
}
