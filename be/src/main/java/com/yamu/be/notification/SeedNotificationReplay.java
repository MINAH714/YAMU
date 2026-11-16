package com.yamu.be.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** Explicit seed operation only: no Spring bean, event publication, bank call or push. */
public final class SeedNotificationReplay {
    private record Event(NotificationWriter.Draft draft, java.time.LocalDateTime at) {}

    private SeedNotificationReplay() {}

    /** Caller owns the transaction. Only the named seed user's fixture events are replayed. */
    public static int replay(JdbcTemplate db, String dataset, long user, Instant since, Instant until) {
        String persona=db.queryForObject("SELECT persona_key FROM qa_seed_member WHERE dataset_key=? AND user_id=?",String.class,dataset,user);
        String prefix=dataset+":"+persona+":";
        var oldRead=db.queryForList("SELECT notification_type,target_type,target_id,read_at FROM notification WHERE user_id=? AND LEFT(deduplication_key,?)=? AND read_at IS NOT NULL",user,prefix.length(),prefix);
        var jdbc=new NamedParameterJdbcTemplate(db);
        var contents=new NotificationContentReader(jdbc);
        var writer=new NotificationWriter(new NotificationSettingsStore(jdbc,new ObjectMapper()),jdbc,
                new DataSourceTransactionManager(db.getDataSource()),Clock.systemUTC());
        var events=new ArrayList<Event>();
        db.query("""
            SELECT payment_transaction_id,approved_at,canceled_at,status FROM payment_transaction
            WHERE user_id=? AND (source='SEED' OR JSON_UNQUOTE(JSON_EXTRACT(merchant_context_snapshot,'$.source'))='QA_LIFE_550')
              AND approved_at>=? AND approved_at<=? AND status IN ('APPROVED','CANCELED')
            """,rs->{
                long id=rs.getLong(1);
                var payment=contents.payment(user,id).orElseThrow();
                events.add(new Event(NotificationMessages.paymentApproved(user,id,payment),rs.getTimestamp(2).toLocalDateTime()));
                var canceled=rs.getTimestamp(3);
                if("CANCELED".equals(rs.getString(4)) && canceled!=null && !canceled.toInstant().isAfter(until))
                    events.add(new Event(NotificationMessages.paymentCanceled(user,id,payment),canceled.toLocalDateTime()));
            },user,Timestamp.from(since),Timestamp.from(until));
        db.query("""
            SELECT card_bill_id,confirmed_amount,withdrawal_date,confirmed_at,paid_at,paid_amount,status
            FROM card_bill WHERE user_id=? AND confirmed_at IS NOT NULL AND confirmed_at<=?
            """,rs->{
                long id=rs.getLong(1);
                var bill=contents.bill(user,id).orElseThrow();
                var confirmed=rs.getTimestamp(4);
                if(!confirmed.toInstant().isBefore(since))
                    events.add(new Event(NotificationMessages.billConfirmed(user,id,bill,rs.getLong(2),rs.getDate(3).toLocalDate(),rs.getLong(2)==0),confirmed.toLocalDateTime()));
                var paid=rs.getTimestamp(5);
                if("PAID".equals(rs.getString(7)) && paid!=null && !paid.toInstant().isBefore(since) && !paid.toInstant().isAfter(until) && rs.getLong(6)>0)
                    events.add(new Event(NotificationMessages.billWithdrawn(user,id,bill,rs.getLong(6),paid.toInstant().atZone(java.time.ZoneId.of("Asia/Seoul")).toLocalDate()),paid.toLocalDateTime()));
            },user,Timestamp.from(until));
        // Remove only the old numbered seed notifications, never unrelated live events.
        db.update("DELETE FROM notification WHERE user_id=? AND LEFT(deduplication_key,?)=?",user,prefix.length(),prefix);
        events.sort(Comparator.comparing(Event::at).thenComparing(e->e.draft().deduplicationKey()));
        int created=0;
        var unreadFrom=until.atZone(java.time.ZoneId.of("Asia/Seoul")).toLocalDate()
                .withDayOfMonth(1).atStartOfDay(java.time.ZoneId.of("Asia/Seoul")).toInstant();
        for(var event:events) if(writer.writeAt(event.draft(),event.at())==NotificationWriter.Result.CREATED) {
            created++;
            // Initial fixture only: earlier months are read; preserve subsequent user read actions on replay.
            if(event.at().toInstant(ZoneOffset.UTC).isBefore(unreadFrom)) db.update("""
                UPDATE notification SET read_at=? WHERE user_id=? AND deduplication_key=? AND read_at IS NULL
                """,Timestamp.from(unreadFrom),user,event.draft().deduplicationKey());
        }
        for(var read:oldRead) db.update("""
            UPDATE notification SET read_at=? WHERE user_id=? AND notification_type=? AND target_type=? AND target_id=?
              AND read_at IS NULL AND created_at<=?
            """,read.get("read_at"),user,read.get("notification_type"),read.get("target_type"),read.get("target_id"),read.get("read_at"));
        return created;
    }
}
