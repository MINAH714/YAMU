package com.yamu.be.seed.qa;
import com.yamu.be.payment.bank.*;
import java.time.*;
import java.util.*;
/** Pure synthetic ledger. No balancing transaction is fabricated to conceal a negative balance. */
public final class QaAssetHistoryPlan {
    private QaAssetHistoryPlan() {}
    public record Event(String id,LocalDate date,BankDirection direction,long amount) {}
    public record Plan(long opening,long closing,long deposits,long withdrawals,List<BankTransaction> rows) {
        public Plan { rows=List.copyOf(rows); }
        @Override public String toString() { return "QaAssetPlan[transactions="+rows.size()+"]"; }
    }
    public static Plan build(List<Event> events,LocalDate from,LocalDate to,long closing) {
        if(from.isAfter(to)||closing<0) throw new IllegalArgumentException("Invalid range or boundary");
        var sorted=events.stream().sorted(Comparator.comparing(Event::date).thenComparing(Event::id)).toList();
        var ids=new HashSet<String>(); long net=0,deposits=0,withdrawals=0;
        for(var e:sorted) {
            if(e.id()==null||!ids.add(e.id())||e.date().isBefore(from)||e.date().isAfter(to)||e.amount()<=0||e.direction()==null)
                throw new IllegalArgumentException("Invalid QA event");
            if(e.direction()==BankDirection.DEPOSIT) { net=Math.addExact(net,e.amount());deposits=Math.addExact(deposits,e.amount()); }
            else { net=Math.subtractExact(net,e.amount());withdrawals=Math.addExact(withdrawals,e.amount()); }
        }
        long opening=Math.subtractExact(closing,net), running=opening;
        if(running<0) throw new IllegalArgumentException("Negative synthetic opening");
        var rows=new ArrayList<BankTransaction>(); LocalDate day=null;int slot=0;
        for(var e:sorted) {
            if(!e.date().equals(day)) { day=e.date();slot=0; }
            if(++slot>36000) throw new IllegalArgumentException("Too many events in a day");
            running=e.direction()==BankDirection.DEPOSIT?Math.addExact(running,e.amount()):Math.subtractExact(running,e.amount());
            if(running<0) throw new IllegalArgumentException("Negative intermediate synthetic balance");
            rows.add(new BankTransaction(e.id(),day,LocalTime.of(8,0).plusSeconds(slot),e.direction(),e.amount(),running,""));
        }
        if(running!=closing) throw new IllegalStateException("Boundary mismatch");
        return new Plan(opening,closing,deposits,withdrawals,rows);
    }
}
