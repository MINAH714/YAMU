package com.yamu.be.seed.qa;

import com.yamu.be.global.crypto.LookupHasher;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import static com.yamu.be.seed.qa.QaSeedSpec.require;

/** Explicit dataset-locked DB-only correction. No authentication changes or financial clients. */
final class QaLifeBenefitRevision {
    private final JdbcTemplate db;
    private final Clock clock;
    private final LookupHasher lookup;
    QaLifeBenefitRevision(JdbcTemplate db,Clock clock,LookupHasher lookup) {this.db=db;this.clock=clock;this.lookup=lookup;}
    void apply(QaSeedSpec spec) {
        require(spec.baseDate().equals(QaLifeScenario.BASE),"Wrong LIFE date");
        require(db.queryForObject("SELECT COUNT(*) FROM qa_seed_member WHERE dataset_key=?",Long.class,spec.datasetKey())==spec.personas().size(),"Membership differs");
        for(var person:spec.personas()) {
            require(person.scenario()==QaSeedSpec.Scenario.LIFE,"Only LIFE supported");
            long user=db.queryForObject("SELECT user_id FROM qa_seed_member WHERE dataset_key=? AND persona_key=? FOR UPDATE",Long.class,spec.datasetKey(),person.key());
            require(db.queryForObject("SELECT COUNT(*) FROM app_user WHERE user_id=? AND ssafy_member_id=?",Long.class,user,person.ssafyMemberId())==1,"Member binding differs");
            long instantPayment=0;
            boolean changedApproval=false;
            for(var p:QaLifeScenario.payments(spec.baseDate())) {
                String key=UUID.nameUUIDFromBytes((spec.datasetKey()+":"+person.key()+":"+p.card()+":"+p.date()+":"+p.slot()).getBytes(StandardCharsets.UTF_8)).toString();
                var rows=db.queryForList("""
                    SELECT t.payment_transaction_id,t.card_product_id,t.approved_amount,m.brand_code
                    FROM payment_transaction t JOIN user_card c USING(user_card_id) JOIN merchant m USING(merchant_id)
                    WHERE t.user_id=? AND c.user_id=? AND c.card_no_hash=?
                      AND c.issuer_account_bank_code=? AND c.issuer_account_hash=? AND t.request_key=?
                      AND t.status='APPROVED' AND t.requested_amount=? AND t.approved_at=?
                      AND (t.source='SEED' OR JSON_UNQUOTE(JSON_EXTRACT(t.merchant_context_snapshot,'$.source'))='QA_LIFE_550')
                    FOR UPDATE
                    """,user,user,lookup.card(person.cards().get(p.card()).cardNo()),
                    person.accounts().get(person.cards().get(p.card()).accountIndex()).bankCode(),
                    lookup.account(person.accounts().get(person.cards().get(p.card()).accountIndex()).bankCode(),
                        person.accounts().get(person.cards().get(p.card()).accountIndex()).accountNo()),key,p.amount(),
                    Timestamp.from(p.date().atStartOfDay(ZoneId.of("Asia/Seoul")).plusMinutes(p.slot()).toInstant()));
                require(rows.size()==1,"Original seed purchase changed; refusing overwrite");
                var row=rows.get(0);
                boolean instant=p.card()==3 && p.date().equals(LocalDate.of(2026,11,8));
                require(p.brand().equals(row.get("brand_code")) || instant && "YAMU_TEST_RESTAURANT".equals(row.get("brand_code")),"Merchant changed");
                long amount=((Number)row.get("approved_amount")).longValue();
                require(amount==QaLifeBenefits.approved(p) || instant && amount==p.amount(),"Approved amount changed");
                if(!instant) continue;
                instantPayment=((Number)row.get("payment_transaction_id")).longValue();
                if(amount==p.amount()) {
                    changedApproval=true;
                    require(db.queryForObject("SELECT COUNT(*) FROM payment_benefit WHERE payment_transaction_id=?",Long.class,instantPayment)==0,"Existing benefit on correction target");
                    require(db.queryForObject("SELECT COUNT(*) FROM payment_limit_usage WHERE payment_transaction_id=?",Long.class,instantPayment)==0,"Existing limit usage on correction target");
                    db.update("""
                        UPDATE payment_transaction t JOIN merchant m ON m.brand_code='GS25'
                        SET t.merchant_id=m.merchant_id,merchant_context_snapshot=JSON_SET(t.merchant_context_snapshot,
                          '$.brandCode','GS25','$.merchantCategoryCode',m.merchant_category_code,'$.merchantName',m.merchant_name,
                          '$.categoryCode',m.merchant_category_code)
                        WHERE t.payment_transaction_id=? AND t.user_id=?
                        """,instantPayment,user);
                    QaLifeBenefits.apply(db,instantPayment,((Number)row.get("card_product_id")).longValue(),p);
                }
            }
            require(instantPayment>0,"Instant fixture missing");
            db.update("""
                UPDATE payment_benefit b JOIN payment_transaction t USING(payment_transaction_id)
                SET b.confirmed_at=?,b.confirmation_source='QA_HISTORY'
                WHERE t.user_id=? AND t.status='APPROVED' AND t.approved_at>=? AND t.approved_at<?
                  AND JSON_UNQUOTE(JSON_EXTRACT(t.merchant_context_snapshot,'$.source'))='QA_LIFE_550'
                  AND b.confirmed_at IS NULL
                """,Timestamp.from(spec.baseDate().atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant()),user,
                Timestamp.from(QaLifeBenefits.SIGNUP.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant()),
                Timestamp.from(LocalDate.of(2026,11,1).atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant()));
            // Regenerate only this dataset's synthetic IBK stream, if installed. Never touch SSAFY rows.
            var streams=db.queryForList("""
                SELECT s.linked_account_id,s.range_from,s.range_to,t.external_id FROM account_history_stream s
                JOIN linked_account a USING(linked_account_id)
                LEFT JOIN qa_seed_funding_boundary f ON f.qa_stream_id=s.stream_id
                LEFT JOIN account_transaction t ON t.transaction_id=f.funding_transaction_id
                WHERE s.user_id=? AND s.dataset_key=? AND s.source='QA_SYNTHETIC' AND a.bank_code='003'
                """,user,spec.datasetKey());
            require(streams.size()<=1,"Multiple IBK streams");
            for(var stream:changedApproval?streams:List.<Map<String,Object>>of()) {
                var input=new QaAssetHistory.Spec(spec.datasetKey(),person.key(),((Number)stream.get("linked_account_id")).longValue(),
                    ((java.sql.Date)stream.get("range_from")).toLocalDate(),((java.sql.Date)stream.get("range_to")).toLocalDate(),
                    0,0,List.of(),"LIFE",(String)stream.get("external_id"));
                var assets=new QaAssetHistory(db,clock);
                assets.run("reset",input);assets.run("apply",input);
                require(assets.run("verify",input).closing()==300000,"IBK closing changed");
            }
            // Only the seeded salary item referenced by this user's settings is renamed.
            // User-created schedules, edited labels and overridden occurrences are preserved.
            db.update("""
                UPDATE cashflow_occurrence o JOIN cashflow_item i USING(cashflow_item_id)
                JOIN app_user u ON u.user_id=i.user_id
                SET o.title='월급'
                WHERE i.user_id=? AND o.user_id=i.user_id AND i.title IN ('급여','월급')
                  AND i.direction='INCOME' AND i.amount=2500000 AND i.recurrence_type='MONTHLY'
                  AND i.recurrence_day_of_month=26 AND o.title='급여' AND o.is_overridden=FALSE
                  AND i.cashflow_item_id=CAST(JSON_UNQUOTE(JSON_EXTRACT(u.forecast_settings,'$.incomeCashflowItemId')) AS UNSIGNED)
                """,user);
            db.update("""
                UPDATE cashflow_item i JOIN app_user u ON u.user_id=i.user_id SET i.title='월급'
                WHERE i.user_id=? AND i.title='급여' AND i.direction='INCOME' AND i.amount=2500000
                  AND i.recurrence_type='MONTHLY' AND i.recurrence_day_of_month=26
                  AND i.cashflow_item_id=CAST(JSON_UNQUOTE(JSON_EXTRACT(u.forecast_settings,'$.incomeCashflowItemId')) AS UNSIGNED)
                """,user);
            com.yamu.be.notification.SeedNotificationReplay.refreshPayment(db,user,instantPayment);
        }
    }
}
