package com.yamu.be.seed.qa;

import java.sql.DriverManager;
import java.time.Instant;
import java.time.ZoneId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** Manual repair for the isolated local preview only; not part of the production jar. */
public final class LocalNotificationReplayCli {
    public static void main(String[] args) throws Exception {
        String url=System.getenv("QA_LOCAL_JDBC_URL");
        if(url==null || !url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/test_[0-9]+\\?.*"))
            throw new IllegalArgumentException("Only the isolated loopback test database is allowed");
        try(var connection=DriverManager.getConnection(url,"root",System.getenv("QA_LOCAL_DB_PASSWORD"))) {
            connection.setAutoCommit(false);
            try {
                var db=new JdbcTemplate(new SingleConnectionDataSource(connection,true));
                String dataset="qa-550-life-1";
                long user=db.queryForObject("SELECT user_id FROM qa_seed_member WHERE dataset_key=? AND persona_key='p0'",Long.class,dataset);
                int created=com.yamu.be.notification.SeedNotificationReplay.replay(db,dataset,user,
                        QaLifeBenefits.SIGNUP.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant(),Instant.now());
                int repeated=com.yamu.be.notification.SeedNotificationReplay.replay(db,dataset,user,
                        QaLifeBenefits.SIGNUP.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant(),Instant.now());
                if(repeated!=0) throw new IllegalStateException("Replay must be idempotent");
                connection.commit();
                System.out.println("Local notifications created="+created+", repeated="+repeated);
            } catch(Exception failure) { connection.rollback(); throw failure; }
        }
    }
}
