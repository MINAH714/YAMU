package com.yamu.be.seed.qa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.*;

/** Portable output of the current-policy engine replay, checked against that engine in integration tests. */
public final class QaLifeBenefits {
    public static final LocalDate SIGNUP = LocalDate.of(2026,4,1);
    private static final Map<String,JsonNode> ROWS = load();
    private QaLifeBenefits() { }
    private static Map<String,JsonNode> load() {
        try(var in=QaLifeBenefits.class.getResourceAsStream("/qa/life-benefits.json")) {
            if(in==null) throw new IllegalStateException("LIFE benefit replay missing");
            var root=new ObjectMapper().readTree(in);
            QaSeedSpec.require(root.path("signup").asText().equals(SIGNUP.toString())
                && root.path("policyAsOf").asText().equals(QaLifeScenario.BASE.toString()),"LIFE policy basis mismatch");
            var rows=new HashMap<String,JsonNode>();
            for(var row:root.path("payments")) QaSeedSpec.require(rows.put(row.path("key").asText(),row)==null,"Duplicate benefit replay");
            return Map.copyOf(rows);
        } catch(java.io.IOException e) { throw new IllegalStateException("LIFE benefit replay invalid",e); }
    }
    public static JsonNode row(QaHistoryPlan.Payment p) {
        if(p.date().isBefore(SIGNUP)) return null;
        var row=ROWS.get(p.card()+":"+p.date()+":"+p.slot());
        QaSeedSpec.require(row!=null && row.path("gross").asLong()==p.amount(),"LIFE purchase/replay mismatch");
        return row;
    }
    public static long approved(QaHistoryPlan.Payment p) { var r=row(p);return p.amount()-(r==null?0:r.path("approvalDiscount").asLong()); }
    public static long billed(QaHistoryPlan.Payment p) { var r=row(p);return approved(p)-(r==null?0:r.path("billingDiscount").asLong()); }
    public static String fingerprint() { return new TreeMap<>(ROWS).toString(); }
    public static void apply(org.springframework.jdbc.core.JdbcTemplate db, long payment, long product, QaHistoryPlan.Payment purchase) {
        var r=QaLifeBenefits.row(purchase);
        if(r==null) return;
        java.sql.Timestamp at=java.sql.Timestamp.from(purchase.date().atStartOfDay(java.time.ZoneId.of("Asia/Seoul")).plusMinutes(purchase.slot()).toInstant());
        db.update("""
            UPDATE payment_transaction SET source='YAMU',payment_channel=?,approved_amount=?,
              recognized_performance_amount=?,merchant_context_snapshot=JSON_SET(merchant_context_snapshot,'$.source','QA_LIFE_550',
              '$.policyAsOf','2026-11-16') WHERE payment_transaction_id=?
            """,r.path("channel").asText(),QaLifeBenefits.approved(purchase),
            r.path("recognized").isNull()?null:r.path("recognized").asLong(),payment);
        for(var b:r.path("benefits")) {
            long benefit=db.queryForObject("SELECT card_benefit_id FROM card_benefit WHERE card_product_id=? AND benefit_code=? AND policy_version=1",Long.class,product,b.path("code").asText());
            db.update("""
                INSERT INTO payment_benefit(payment_transaction_id,card_product_id,card_benefit_id,
                  benefit_value,value_unit,krw_amount,created_at) VALUES (?,?,?,?,?,?,?)
                """,payment,product,benefit,b.path("value").decimalValue(),b.path("unit").asText(),
                b.path("krw").isNull()?null:b.path("krw").asLong(),at);
        }
        for(var u:r.path("limits")) {
            long group=db.queryForObject("SELECT limit_group_id FROM limit_group WHERE card_product_id=? AND group_code=? AND policy_version=1",Long.class,product,u.path("code").asText());
            db.update("""
                INSERT INTO payment_limit_usage(payment_transaction_id,card_product_id,limit_group_id,
                  remaining_before,consumed_value,usage_date,usage_month,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?)
                """,payment,product,group,u.path("before").asLong(),u.path("used").asLong(),purchase.date(),purchase.date().withDayOfMonth(1),at,at);
        }
    }
}
