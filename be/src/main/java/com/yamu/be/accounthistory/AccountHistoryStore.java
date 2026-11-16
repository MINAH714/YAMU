package com.yamu.be.accounthistory;

import com.yamu.be.account.domain.LinkedAccount;
import com.yamu.be.assetflow.DailyBalanceReconstructor;
import com.yamu.be.payment.bank.*;
import com.yamu.be.global.exception.*;
import jakarta.persistence.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** All methods finish before a financial API call starts. No account numbers or keys are stored here. */
@Service
public class AccountHistoryStore {
    private static final ZoneId KST=ZoneId.of("Asia/Seoul");
    @PersistenceContext private EntityManager em;
    public record Ticket(long runId, long streamId, long generation) {}
    public record Day(Long balance, HistorySource source) {}
    public record View(Map<LocalDate,Day> days, String reason) {
        public View { days=Map.copyOf(days); }
    }

    @Transactional
    public Ticket begin(long user, long account, LocalDate from, LocalDate to) {
        var owner=em.find(LinkedAccount.class,account,LockModeType.PESSIMISTIC_WRITE);
        if(owner==null || !Objects.equals(owner.getUserId(),user) || !owner.isConnected())
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        var streams=em.createQuery("select s from HistoryStream s where s.linkedAccountId=:a and s.source=:source",HistoryStream.class)
                .setParameter("a",account).setParameter("source",HistorySource.SSAFY).getResultList();
        var stream=streams.isEmpty()?new HistoryStream(account,user):streams.get(0);
        if(stream.id==null) em.persist(stream);
        stream.generation=Math.incrementExact(stream.generation);
        var run=new HistoryRun(stream,from,to); em.persist(run); em.flush();
        return new Ticket(run.id,stream.id,stream.generation);
    }

    @Transactional
    public void failed(Ticket ticket, String reason) {
        var stream=em.find(HistoryStream.class,ticket.streamId(),LockModeType.PESSIMISTIC_WRITE);
        var run=em.find(HistoryRun.class,ticket.runId());
        if(stream==null || run==null) return;
        run.status=stream.generation==ticket.generation()?reason:"SUPERSEDED";
    }

    @Transactional
    public String complete(Ticket ticket, BankHistory history) {
        var stream=em.find(HistoryStream.class,ticket.streamId(),LockModeType.PESSIMISTIC_WRITE);
        var run=em.find(HistoryRun.class,ticket.runId());
        if(stream==null || run==null) return "SUPERSEDED";
        if(stream.generation!=ticket.generation()) { run.status="SUPERSEDED"; return run.status; }
        run.reportedCount=history.reportedCount(); run.receivedCount=(long)history.transactions().size();
        run.queriedAt=LocalDateTime.ofInstant(history.queriedAt(),ZoneOffset.UTC);
        if(!run.rangeFrom.equals(history.from()) || !run.rangeTo.equals(history.to()) ||
                !history.complete() || history.direction()!=null ||
                history.transactions().stream().anyMatch(r->!r.bankTransactionNo().matches("[\\x21-\\x7E]{1,128}"))) {
            run.status="HISTORY_INCOMPLETE"; return run.status;
        }
        var incoming=new HashMap<String,BankTransaction>();
        history.transactions().forEach(r->incoming.put(r.bankTransactionNo(),r));
        var existing=rows(stream.id,history.from(),history.to());
        boolean changed=false;
        for(var old:existing) {
            var row=incoming.get(old.externalId);
            if(row==null || !old.contentHash.equals(HistoryTransaction.hash(row))) {
                invalidate(stream.id,history.from()); run.status="SOURCE_CONFLICT"; return run.status;
            }
        }
        var matched=new HashMap<String,HistoryTransaction>();
        if(!incoming.isEmpty()) {
            em.createQuery("select t from HistoryTransaction t where t.streamId=:s and t.externalId in :ids",HistoryTransaction.class)
                    .setParameter("s",stream.id).setParameter("ids",incoming.keySet()).getResultList()
                    .forEach(t->matched.put(t.externalId,t));
        }
        for(var row:history.transactions()) {
            var prior=matched.get(row.bankTransactionNo());
            if(prior!=null && !prior.contentHash.equals(HistoryTransaction.hash(row))) {
                invalidate(stream.id,history.from()); run.status="SOURCE_CONFLICT"; return run.status;
            }
            if(prior==null) changed=true;
        }
        var priorOpening=opening(stream.id,history.from().minusDays(1));
        Long opening=priorOpening==null?null:priorOpening.balance();
        boolean hadOpening=opening!=null;
        var result=new DailyBalanceReconstructor().reconstruct(history,opening);
        if(result.reason()!=DailyBalanceReconstructor.Reason.OK && result.reason()!=DailyBalanceReconstructor.Reason.NO_ANCHOR) {
            invalidate(stream.id,history.from()); run.status=result.reason().name(); return run.status;
        }
        if(changed) invalidate(stream.id,history.from());
        var known=new HashSet<String>();
        existing.forEach(r->known.add(r.externalId));
        for(var row:history.transactions()) if(!known.contains(row.bankTransactionNo())) {
            var transaction=new HistoryTransaction(stream.id,row);
            em.persist(transaction);
            matched.put(row.bankTransactionNo(),transaction);
        }
        Long firstTransactionId=null;
        if(opening==null && !history.transactions().isEmpty()) {
            var first=history.transactions().stream().min(Comparator.comparing(BankTransaction::date).thenComparing(BankTransaction::time)).orElseThrow();
            opening=first.direction()==BankDirection.DEPOSIT?Math.subtractExact(first.balanceAfter(),first.amount()):Math.addExact(first.balanceAfter(),first.amount());
            firstTransactionId=matched.get(first.bankTransactionNo()).id;
        }
        var coverage=new HistoryCoverage(run,opening);
        if(priorOpening!=null) {
            coverage.openingEvidence=HistoryCoverage.OpeningEvidence.PREVIOUS_COVERAGE;
            coverage.openingCoverageId=priorOpening.coverageId();
        } else if(firstTransactionId!=null) {
            coverage.openingEvidence=HistoryCoverage.OpeningEvidence.FIRST_TRANSACTION;
            coverage.openingTransactionId=firstTransactionId;
        }
        // The first transaction proves its day's opening, never an account's existence in earlier days.
        if (!history.transactions().isEmpty() && !hadOpening) {
            coverage.rangeFrom=history.transactions().stream().map(BankTransaction::date).min(LocalDate::compareTo).orElseThrow();
        }
        em.persist(coverage); em.flush();
        run.status=result.reason().name(); return run.status;
    }

    private void invalidate(Long stream,LocalDate from) {
        // Conservative invalidation also removes dependent carried balances; originals stay stored for audit.
        for(var c:em.createQuery("select c from HistoryCoverage c where c.streamId=:s and c.rangeTo>=:f and c.valid=true",HistoryCoverage.class)
                .setParameter("s",stream).setParameter("f",from).getResultList()) c.valid=false;
    }
    private record Opening(long balance,long coverageId) {}
    private Opening opening(Long stream,LocalDate date) {
        var candidates=em.createQuery("select c from HistoryCoverage c where c.valid=true and c.streamId=:s and c.rangeTo=:d and c.openingBalance is not null order by c.id desc",HistoryCoverage.class)
                .setParameter("s",stream).setParameter("d",date).getResultList();
        for(var coverage:candidates) {
            var result=reconstruct(coverage);
            if(result.reason()==DailyBalanceReconstructor.Reason.OK)
                return new Opening(result.points().get(result.points().size()-1).balance(),coverage.id);
        }
        return null;
    }
    private DailyBalanceReconstructor.Result reconstruct(HistoryCoverage coverage) {
        var run=em.find(HistoryRun.class,coverage.runId);
        var transactions=rows(coverage.streamId,coverage.rangeFrom,coverage.rangeTo).stream().map(HistoryTransaction::row).toList();
        var snapshot=new BankHistory(coverage.rangeFrom,coverage.rangeTo,null,run.reportedCount,transactions,run.queriedAt.toInstant(ZoneOffset.UTC));
        return new DailyBalanceReconstructor().reconstruct(snapshot,coverage.openingBalance);
    }
    private boolean qaBoundaryValid(HistoryStream qa) {
        var actuals=em.createQuery("select c from HistoryCoverage c, HistoryStream s where c.streamId=s.id and s.linkedAccountId=:a and s.source=:source and c.valid=true and c.rangeFrom=:boundary and c.openingEvidence=:evidence",HistoryCoverage.class)
                .setParameter("a",qa.linkedAccountId).setParameter("source",HistorySource.SSAFY)
                .setParameter("boundary",qa.rangeTo.plusDays(1))
                .setParameter("evidence",HistoryCoverage.OpeningEvidence.FIRST_TRANSACTION).getResultList();
        var synthetic=em.createQuery("select c from HistoryCoverage c where c.streamId=:s and c.valid=true and c.rangeFrom=:f and c.rangeTo=:t",HistoryCoverage.class)
                .setParameter("s",qa.id).setParameter("f",qa.rangeFrom).setParameter("t",qa.rangeTo).getResultList();
        for(var coverage:synthetic) {
            var result=reconstruct(coverage);
            if(result.reason()!=DailyBalanceReconstructor.Reason.OK) continue;
            Long closing=result.points().get(result.points().size()-1).balance();
            if(actuals.stream().anyMatch(c->Objects.equals(c.openingBalance,closing))) return true;
        }
        return false;
    }
    private List<HistoryTransaction> rows(Long stream,LocalDate from,LocalDate to) {
        return em.createQuery("select t from HistoryTransaction t where t.streamId=:s and t.occurredAt>=:f and t.occurredAt<:t order by t.occurredAt",HistoryTransaction.class)
                .setParameter("s",stream).setParameter("f",utc(from)).setParameter("t",utc(to.plusDays(1))).getResultList();
    }
    private static LocalDateTime utc(LocalDate date) {
        return date.atStartOfDay(KST).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    @Transactional(readOnly=true)
    public View read(long user,long account,LocalDate from,LocalDate to) {
        var owner=em.find(LinkedAccount.class,account);
        if(owner==null || !Objects.equals(owner.getUserId(),user) || !owner.isConnected())
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        var streams=em.createQuery("select s from HistoryStream s where s.linkedAccountId=:a and s.userId=:u and s.active=true",HistoryStream.class)
                .setParameter("a",account).setParameter("u",user).getResultList();
        var days=new HashMap<LocalDate,Day>();
        var qa=streams.stream().filter(s->s.source==HistorySource.QA_SYNTHETIC).findFirst().orElse(null);
        if(qa!=null) {
            Number members=(Number)em.createNativeQuery("select count(*) from qa_seed_member where dataset_key=:d and user_id=:u")
                    .setParameter("d",qa.datasetKey).setParameter("u",user).getSingleResult();
            if(members.longValue()!=1) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        boolean qaBoundaryValid=qa==null || qaBoundaryValid(qa);
        String reason=qaBoundaryValid?"NO_ANCHOR":"QA_BOUNDARY_UNVERIFIED";
        for(var stream:streams) {
            if(stream.source==HistorySource.QA_SYNTHETIC && !qaBoundaryValid) continue;
            var coverages=em.createQuery("select c from HistoryCoverage c where c.streamId=:s and c.valid=true and c.rangeFrom<=:t and c.rangeTo>=:f order by c.id",HistoryCoverage.class)
                    .setParameter("s",stream.id).setParameter("f",from).setParameter("t",to).getResultList();
            for(var c:coverages) {
                var result=reconstruct(c);
                reason=result.reason().name();
                for(var point:result.points()) {
                    var d=point.date();
                    boolean synthetic=qa!=null && !d.isBefore(qa.rangeFrom) && !d.isAfter(qa.rangeTo);
                    if(d.isBefore(from)||d.isAfter(to)||point.balance()==null || synthetic!=(stream.source==HistorySource.QA_SYNTHETIC)) continue;
                    days.put(d,new Day(point.balance(),stream.source));
                }
            }
        }
        if (!qaBoundaryValid && qa!=null && !to.isBefore(qa.rangeFrom) && !from.isAfter(qa.rangeTo)) reason="QA_BOUNDARY_UNVERIFIED";
        return new View(days,days.size()==java.time.temporal.ChronoUnit.DAYS.between(from,to)+1?"OK":reason.equals("OK")?"HISTORY_INCOMPLETE":reason);
    }
}
