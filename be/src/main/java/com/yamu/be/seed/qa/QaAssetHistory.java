package com.yamu.be.seed.qa;

import com.yamu.be.assetflow.DailyBalanceReconstructor;
import com.yamu.be.payment.bank.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Caller owns one JDBC transaction. Dataset ownership is resolved, never supplied as a user id. */
public final class QaAssetHistory {
    private static final ZoneId KST=ZoneId.of("Asia/Seoul");
    private final JdbcTemplate db; private final Clock clock;
    public QaAssetHistory(JdbcTemplate db,Clock clock) { this.db=db;this.clock=clock; }
    public record Spec(String datasetKey,String personaKey,long accountId,LocalDate from,LocalDate to,
                       long monthlyIncome,long monthlyRent,List<QaAssetHistoryPlan.Event> extraEvents) {}
    public record Report(String command,int transactions,long opening,long deposits,long withdrawals,long closing) {}
    private record Boundary(long stream,long coverage,long balance) {}

    public Report run(String command,Spec spec) {
        require(spec.datasetKey()!=null&&spec.personaKey()!=null&&spec.accountId()>0,"Missing ownership");
        long user=db.queryForObject("SELECT user_id FROM qa_seed_member WHERE dataset_key=? AND persona_key=?",Long.class,spec.datasetKey(),spec.personaKey());
        // Same account lock as financial sync initialization; held only during local work.
        require(db.queryForObject("SELECT user_id FROM linked_account WHERE linked_account_id=? FOR UPDATE",Long.class,spec.accountId())==user,"Wrong owner");
        if(command.equals("reset")||command.equals("purge")) {
            var ids=db.queryForList("SELECT stream_id FROM account_history_stream WHERE linked_account_id=? AND user_id=? AND source='QA_SYNTHETIC' AND dataset_key=?",Long.class,spec.accountId(),user,spec.datasetKey());
            for(long id:ids) removeStream(db,id);
            return new Report(command,0,0,0,0,0);
        }
        require(spec.from()!=null&&spec.to()!=null&&!spec.from().isAfter(spec.to())&&spec.to().isBefore(LocalDate.now(clock.withZone(KST)))
                &&spec.monthlyIncome()>=0&&spec.monthlyRent()>=0,"Invalid range");
        var boundary=boundary(spec);
        var events=events(user,spec);
        var plan=QaAssetHistoryPlan.build(events,spec.from(),spec.to(),boundary.balance());
        var snapshot=new BankHistory(spec.from(),spec.to(),null,(long)plan.rows().size(),plan.rows(),clock.instant());
        require(new DailyBalanceReconstructor().reconstruct(snapshot,plan.opening()).reason()==DailyBalanceReconstructor.Reason.OK,"Invalid ledger");
        String fingerprint=hash(spec.toString()+"|"+boundary.stream()+"|"+boundary.balance()+"|"+plan.opening()+"|"+plan.rows().stream()
                .map(r->r.bankTransactionNo()+"|"+r.date()+"|"+r.time()+"|"+r.direction()+"|"+r.amount()+"|"+r.balanceAfter()).toList());
        var ids=db.queryForList("SELECT stream_id FROM account_history_stream WHERE linked_account_id=? AND source='QA_SYNTHETIC'",Long.class,spec.accountId());
        if(command.equals("apply")&&ids.isEmpty()) {
            Timestamp now=Timestamp.from(clock.instant());
            db.update("""
                INSERT INTO account_history_stream(linked_account_id,user_id,source,dataset_key,generation,active,range_from,range_to,input_hash,created_at,updated_at,version)
                VALUES (?,?,'QA_SYNTHETIC',?,1,FALSE,?,?,?,?,?,0)
                """,spec.accountId(),user,spec.datasetKey(),spec.from(),spec.to(),fingerprint,now,now);
            long stream=db.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
            db.update("""
                INSERT INTO account_history_sync_run(stream_id,generation,range_from,range_to,status,reported_count,received_count,queried_at,created_at,updated_at,version)
                VALUES (?,1,?,?,'OK',?,?,?,?,?,0)
                """,stream,spec.from(),spec.to(),plan.rows().size(),plan.rows().size(),now,now,now);
            long run=db.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
            for(var row:plan.rows()) db.update("""
                INSERT INTO account_transaction(stream_id,external_id,occurred_at,direction,amount,balance_after,content_hash,created_at)
                VALUES (?,?,?,?,?,?,?,?)
                """,stream,row.bankTransactionNo(),Timestamp.from(row.date().atTime(row.time()).atZone(KST).toInstant()),row.direction().name(),
                    row.amount(),row.balanceAfter(),hash(row.date()+"|"+row.time()+"|"+row.direction()+"|"+row.amount()+"|"+row.balanceAfter()),now);
            db.update("""
                INSERT INTO account_history_coverage(stream_id,run_id,range_from,range_to,valid,opening_balance,opening_evidence,created_at,updated_at,version)
                VALUES (?,?,?,?,TRUE,?,'QA_SCENARIO',?,?,0)
                """,stream,run,spec.from(),spec.to(),plan.opening(),now,now);
            // Proof is locked and rechecked in the same transaction before activation.
            require(boundary(spec).balance()==plan.closing(),"Boundary changed");
            db.update("UPDATE account_history_stream SET active=TRUE,updated_at=?,version=version+1 WHERE stream_id=?",now,stream);
            ids=List.of(stream);
        }
        if(!command.equals("plan")) {
            require(ids.size()==1,"No installed QA history");
            long stream=ids.get(0);
            require(db.queryForObject("SELECT COUNT(*) FROM account_history_stream WHERE stream_id=? AND user_id=? AND dataset_key=? AND input_hash=? AND active=TRUE",
                    Long.class,stream,user,spec.datasetKey(),fingerprint)==1,"Inputs changed; reset only QA asset history first");
            var installed=db.query("""
                SELECT external_id,occurred_at,direction,amount,balance_after FROM account_transaction WHERE stream_id=? ORDER BY occurred_at
                """,(rs,n)->{
                    var time=rs.getTimestamp(2).toInstant().atZone(KST);
                    return new BankTransaction(rs.getString(1),time.toLocalDate(),time.toLocalTime(),BankDirection.valueOf(rs.getString(3)),rs.getLong(4),rs.getLong(5),"");
                },stream);
            require(installed.equals(plan.rows()),"Stored rows differ");
            require(db.queryForObject("SELECT COUNT(*) FROM account_history_coverage WHERE stream_id=? AND valid=TRUE AND opening_balance=?",
                    Long.class,stream,plan.opening())==1,"Missing valid coverage");
        }
        return new Report(command,plan.rows().size(),plan.opening(),plan.deposits(),plan.withdrawals(),plan.closing());
    }

    private Boundary boundary(Spec spec) {
        var found=db.query("""
            SELECT s.stream_id,c.coverage_id,c.opening_balance
            FROM account_history_stream s JOIN account_history_coverage c ON c.stream_id=s.stream_id
            WHERE s.linked_account_id=? AND s.source='SSAFY' AND c.valid=TRUE
              AND c.range_from=? AND c.opening_evidence='FIRST_TRANSACTION'
            ORDER BY c.coverage_id DESC LIMIT 1 FOR UPDATE
            """,(rs,n)->new Boundary(rs.getLong(1),rs.getLong(2),rs.getLong(3)),spec.accountId(),spec.to().plusDays(1));
        require(found.size()==1,"No verified actual opening boundary");
        var proof=found.get(0);
        var first=db.queryForObject("SELECT MIN(occurred_at) FROM account_transaction WHERE stream_id=?",Timestamp.class,proof.stream());
        require(first!=null&&first.toInstant().atZone(KST).toLocalDate().equals(spec.to().plusDays(1)),"Boundary must be first real history date");
        return proof;
    }
    private List<QaAssetHistoryPlan.Event> events(long user,Spec spec) {
        var events=new ArrayList<QaAssetHistoryPlan.Event>();
        if(spec.extraEvents()!=null) events.addAll(spec.extraEvents());
        // These are scenario events, not real bank debits or bill rows.
        db.query("""
            SELECT p.payment_transaction_id,p.approved_at,p.approved_amount,cp.card_type,uc.payment_day
            FROM payment_transaction p JOIN user_card uc ON uc.user_card_id=p.user_card_id
            JOIN card_product cp ON cp.card_product_id=uc.card_product_id
            WHERE p.user_id=? AND uc.linked_account_id=? AND p.status='APPROVED' AND p.approved_amount>0
            ORDER BY p.payment_transaction_id
            """,rs->{
                LocalDate date=rs.getTimestamp(2).toInstant().atZone(KST).toLocalDate();
                String prefix="CHECK-";
                if(rs.getString(4).equals("CREDIT")) {
                    int day=rs.getInt(5);require(!rs.wasNull()&&day>=1&&day<=31,"Unknown credit payment day");
                    YearMonth month=YearMonth.from(date).plusMonths(1);
                    date=month.atDay(Math.min(day,month.lengthOfMonth()));prefix="CREDIT-SCENARIO-";
                }
                if(!date.isBefore(spec.from())&&!date.isAfter(spec.to()))
                    events.add(new QaAssetHistoryPlan.Event(prefix+rs.getLong(1),date,BankDirection.WITHDRAWAL,rs.getLong(3)));
            },user,spec.accountId());
        for(var month=YearMonth.from(spec.from());!month.isAfter(YearMonth.from(spec.to()));month=month.plusMonths(1)) {
            LocalDate date=month.atDay(1).isBefore(spec.from())?spec.from():month.atDay(1);
            if(spec.monthlyIncome()>0) events.add(new QaAssetHistoryPlan.Event("00-INCOME-"+month,date,BankDirection.DEPOSIT,spec.monthlyIncome()));
            if(spec.monthlyRent()>0) events.add(new QaAssetHistoryPlan.Event("01-RENT-"+month,date,BankDirection.WITHDRAWAL,spec.monthlyRent()));
        }
        return events;
    }
    /** Only called after proving ownership and taking the account lock. Financial originals are untouched. */
    static void removeStream(JdbcTemplate db,long stream) {
        require(db.queryForObject("SELECT COUNT(*) FROM account_history_stream WHERE stream_id=? AND source='QA_SYNTHETIC'",Long.class,stream)==1,"Only synthetic streams can be removed");
        db.update("UPDATE account_history_stream SET active=FALSE,generation=generation+1,version=version+1 WHERE stream_id=?",stream);
        for(String table:List.of("account_history_coverage","account_history_sync_run","account_transaction"))
            db.update("DELETE FROM "+table+" WHERE stream_id=?",stream);
        db.update("DELETE FROM account_history_stream WHERE stream_id=?",stream);
    }
    static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    static void require(boolean condition,String message) { if(!condition) throw new IllegalArgumentException(message); }
}
