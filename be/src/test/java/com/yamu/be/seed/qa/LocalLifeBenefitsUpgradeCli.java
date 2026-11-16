package com.yamu.be.seed.qa;

import java.sql.*;
import java.time.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** Explicit local-only upgrade of existing fixture IDs. Default is a rolled-back dry run. */
public final class LocalLifeBenefitsUpgradeCli {
    private static final ZoneId KST=ZoneId.of("Asia/Seoul");
    public static void main(String[] args) throws Exception {
        String url=System.getenv("QA_LOCAL_JDBC_URL");
        QaSeedSpec.require(url!=null && url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/test_[0-9]+\\?.*"),"Only isolated loopback test DB allowed");
        try(var c=DriverManager.getConnection(url,"root",System.getenv("QA_LOCAL_DB_PASSWORD"))) {
            c.setAutoCommit(false);
            try {
                var db=new JdbcTemplate(new SingleConnectionDataSource(c,true));
                upgrade(db);
                if("true".equals(System.getenv("QA_LOCAL_BENEFITS_COMMIT"))) { c.commit(); System.out.println("COMMITTED local LIFE benefit upgrade"); }
                else { c.rollback(); System.out.println("DRY RUN passed; rolled back all changes"); }
            } catch(Exception e) {c.rollback();throw e;}
        }
    }
    static void upgrade(JdbcTemplate db) {
        String dataset="qa-550-life-1";
        long user=db.queryForObject("SELECT user_id FROM qa_seed_member WHERE dataset_key=? AND persona_key='p0' FOR UPDATE",Long.class,dataset);
        var purchases=QaLifeScenario.payments(QaLifeScenario.BASE);
        var identityBefore=db.queryForList("SELECT payment_transaction_id,request_key,requested_amount,approved_amount,status FROM payment_transaction WHERE user_id=? ORDER BY payment_transaction_id",user);
        var settingsBefore=db.queryForList("SELECT pin_hash,forecast_settings,payment_auto_select,notification_settings FROM app_user WHERE user_id=?",user);
        var actualBefore=db.queryForList("SELECT t.* FROM account_transaction t JOIN account_history_stream s USING(stream_id) WHERE s.user_id=? AND s.source='SSAFY' ORDER BY transaction_id",user);
        long readBefore=db.queryForObject("SELECT COUNT(*) FROM notification WHERE user_id=? AND read_at IS NOT NULL",Long.class,user);
        long existing=db.queryForObject("SELECT COUNT(*) FROM payment_transaction WHERE user_id=? AND JSON_UNQUOTE(JSON_EXTRACT(merchant_context_snapshot,'$.source'))='QA_LIFE_550'",Long.class,user);
        QaSeedSpec.require(existing==0 || existing==234,"Partial benefit migration found");
        if(existing==234) { verify(db,user); System.out.println("Already upgraded; no writes");return; }
        String[] names={"KB국민 노리 체크","신한카드 처음","신한카드 Mr.Life","IBK나라사랑카드"};
        Set<Long> bills=new HashSet<>();
        for(var p:purchases) {
            String key=UUID.nameUUIDFromBytes((dataset+":p0:"+p.card()+":"+p.date()+":"+p.slot()).getBytes(StandardCharsets.UTF_8)).toString();
            var rows=db.queryForList("SELECT t.payment_transaction_id,t.card_product_id,t.requested_amount,t.status,t.source,cp.product_name FROM payment_transaction t JOIN card_product cp USING(card_product_id) WHERE t.user_id=? AND t.request_key=? FOR UPDATE",user,key);
            QaSeedSpec.require(rows.size()==1,"Seed purchase missing");
            var row=rows.get(0);
            QaSeedSpec.require(names[p.card()].equals(row.get("product_name")) && ((Number)row.get("requested_amount")).longValue()==p.amount() && "APPROVED".equals(row.get("status")) && "SEED".equals(row.get("source")),"Original purchase changed; stop rather than overwrite");
            long payment=((Number)row.get("payment_transaction_id")).longValue();
            QaSeedSpec.require(db.queryForObject("SELECT COUNT(*) FROM payment_benefit WHERE payment_transaction_id=?",Long.class,payment)==0,"Existing benefit must not be overwritten");
            QaLifeBenefits.apply(db,payment,((Number)row.get("card_product_id")).longValue(),p);
            bills.addAll(db.queryForList("SELECT card_bill_id FROM bill_item WHERE payment_transaction_id=?",Long.class,payment));
        }
        db.update("""
            INSERT INTO bill_item_discount(card_bill_id,bill_item_id,payment_benefit_id,amount,created_at)
            SELECT b.card_bill_id,i.bill_item_id,pb.payment_benefit_id,pb.krw_amount,
              DATE_SUB(TIMESTAMP(DATE_ADD(b.period_end,INTERVAL 1 DAY)),INTERVAL 9 HOUR)
            FROM card_bill b JOIN bill_item i USING(card_bill_id)
            JOIN payment_transaction t ON t.payment_transaction_id=i.payment_transaction_id
            JOIN payment_benefit pb ON pb.payment_transaction_id=t.payment_transaction_id
            JOIN card_benefit cb USING(card_benefit_id)
            WHERE b.user_id=? AND b.period_end<? AND cb.benefit_type='DISCOUNT' AND cb.application_timing='AT_BILLING'
              AND pb.krw_amount>0 AND JSON_UNQUOTE(JSON_EXTRACT(t.merchant_context_snapshot,'$.source'))='QA_LIFE_550'
            """,user,QaLifeScenario.BASE);
        for(long bill:bills) {
            db.update("UPDATE card_bill b SET confirmed_amount=(SELECT SUM(billed_amount) FROM bill_item WHERE card_bill_id=b.card_bill_id)-COALESCE((SELECT SUM(amount) FROM bill_item_discount WHERE card_bill_id=b.card_bill_id),0) WHERE card_bill_id=? AND status IN ('PAID','CONFIRMED')",bill);
            db.update("UPDATE card_bill SET paid_amount=confirmed_amount WHERE card_bill_id=? AND status='PAID'",bill);
        }
        db.update("""
            INSERT INTO monthly_limit_usage(user_card_id,card_product_id,limit_group_id,usage_month,used_value,created_at,updated_at)
            SELECT t.user_card_id,u.card_product_id,u.limit_group_id,u.usage_month,SUM(u.consumed_value),UTC_TIMESTAMP(),UTC_TIMESTAMP()
            FROM payment_limit_usage u JOIN payment_transaction t USING(payment_transaction_id)
            JOIN limit_group g USING(limit_group_id)
            WHERE t.user_id=? AND u.restored_at IS NULL AND g.period_type='MONTHLY'
            GROUP BY t.user_card_id,u.card_product_id,u.limit_group_id,u.usage_month
            ON DUPLICATE KEY UPDATE used_value=VALUES(used_value),updated_at=VALUES(updated_at)
            """,user);
        db.update("UPDATE card_monthly_performance m JOIN user_card c USING(user_card_id) SET m.recognized_amount=(SELECT COALESCE(SUM(t.recognized_performance_amount),0) FROM payment_transaction t WHERE t.user_card_id=m.user_card_id AND t.performance_month=m.performance_month AND t.status='APPROVED') WHERE c.user_id=?",user);
        Timestamp signup=Timestamp.from(QaLifeBenefits.SIGNUP.atStartOfDay(KST).toInstant());
        db.update("UPDATE app_user SET created_at=? WHERE user_id=?",signup,user);
        db.update("UPDATE user_card SET registered_at=? WHERE user_id=?",signup,user);
        db.update("UPDATE linked_account SET connected_at=? WHERE user_id=?",signup,user);
        var history=new QaAssetHistory(db,Clock.systemUTC());
        String[] banks={"004","088","003"};
        for(int i=0;i<3;i++) {
            long account=db.queryForObject("SELECT linked_account_id FROM linked_account WHERE user_id=? AND bank_code=?",Long.class,user,banks[i]);
            var spec=new QaAssetHistory.Spec(dataset,"p0",account,QaLifeScenario.FROM,QaLifeScenario.BASE.minusDays(1),0,0,List.of(),"LIFE","LOCAL-FIXTURE-FUNDING-"+i);
            history.run("reset",spec); history.run("apply",spec);
            QaSeedSpec.require(history.run("verify",spec).closing()==QaLifeScenario.startingBalance(i),"Closing balance changed");
        }
        com.yamu.be.notification.LocalSeedBillNotificationRefresh.refresh(db,user);
        QaSeedSpec.require(identityBefore.equals(db.queryForList("SELECT payment_transaction_id,request_key,requested_amount,approved_amount,status FROM payment_transaction WHERE user_id=? ORDER BY payment_transaction_id",user)),"Purchase identities/amounts changed");
        QaSeedSpec.require(settingsBefore.equals(db.queryForList("SELECT pin_hash,forecast_settings,payment_auto_select,notification_settings FROM app_user WHERE user_id=?",user)),"User settings changed");
        QaSeedSpec.require(actualBefore.equals(db.queryForList("SELECT t.* FROM account_transaction t JOIN account_history_stream s USING(stream_id) WHERE s.user_id=? AND s.source='SSAFY' ORDER BY transaction_id",user)),"Financial source rows changed");
        QaSeedSpec.require(readBefore==db.queryForObject("SELECT COUNT(*) FROM notification WHERE user_id=? AND read_at IS NOT NULL",Long.class,user),"Read state changed");
        verify(db,user);
    }
    private static void verify(JdbcTemplate db,long user) {
        QaSeedSpec.require(db.queryForObject("SELECT SUM(confirmed_amount) FROM card_bill WHERE user_id=? AND billing_month='2026-11-01'",Long.class,user)==896500,"November bill mismatch");
        QaSeedSpec.require(db.queryForObject("SELECT SUM(d.amount) FROM bill_item_discount d JOIN card_bill b USING(card_bill_id) WHERE b.user_id=?",Long.class,user)==19000,"Billing discount mismatch");
        System.out.println("Verified November bill=896500; historical/current billing discounts=19000; seed purchase IDs preserved");
    }
}
