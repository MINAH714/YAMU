package com.yamu.be.notification;

import java.time.ZoneId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Local fixture correction only; preserves notification IDs, dates and read state. */
public final class LocalSeedBillNotificationRefresh {
    public static void refresh(JdbcTemplate db, long user) {
        var contents = new NotificationContentReader(new NamedParameterJdbcTemplate(db));
        db.query("SELECT card_bill_id,confirmed_amount,withdrawal_date,paid_amount,paid_at FROM card_bill WHERE user_id=? AND confirmed_at IS NOT NULL", rs -> {
            long id = rs.getLong(1);
            var bill = contents.bill(user, id).orElseThrow();
            update(db, NotificationMessages.billConfirmed(user, id, bill, rs.getLong(2), rs.getDate(3).toLocalDate(), rs.getLong(2)==0));
            if (rs.getTimestamp(5)!=null) update(db, NotificationMessages.billWithdrawn(user, id, bill, rs.getLong(4), rs.getTimestamp(5).toInstant().atZone(ZoneId.of("Asia/Seoul")).toLocalDate()));
        }, user);
    }
    private static void update(JdbcTemplate db, NotificationWriter.Draft d) {
        db.update("UPDATE notification SET title=?,body=? WHERE user_id=? AND deduplication_key=?", d.title(), d.body(), d.userId(), d.deduplicationKey());
    }
}
