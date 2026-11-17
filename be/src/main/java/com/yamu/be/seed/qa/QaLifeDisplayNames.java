package com.yamu.be.seed.qa;

import static com.yamu.be.seed.qa.QaSeedSpec.require;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yamu.be.global.crypto.LookupHasher;
import com.yamu.be.notification.SeedNotificationReplay;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.ZoneId;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Explicit, dataset-locked presentation revision. No bank, authentication or payment events. */
final class QaLifeDisplayNames {
    private static final ZoneId KST=ZoneId.of("Asia/Seoul");
    private static final String RESTAURANT="YAMU_TEST_RESTAURANT";
    private static final Set<String> NAMES=Set.of("한솥도시락","역전우동0410","홍콩반점0410","새마을식당","메가MGC커피");
    private final JdbcTemplate db;
    private final LookupHasher lookup;
    private final Clock clock;
    QaLifeDisplayNames(JdbcTemplate db,LookupHasher lookup,Clock clock) {this.db=db;this.lookup=lookup;this.clock=clock;}

    /** Display-only demo aliases; never change the rule engine's original merchant/brand/category. */
    static String name(QaHistoryPlan.Payment row) {
        if(row.brand().equals("YAMU_TEST_CAFE")) return "메가MGC커피";
        if(!row.brand().equals(RESTAURANT)) return null;
        if(row.amount()>=45_000) return row.amount()>=80_000 || row.date().getDayOfMonth()==23 ? "새마을식당" : "홍콩반점0410";
        String[] lunch={"한솥도시락","역전우동0410","홍콩반점0410"};
        return lunch[Math.floorMod(row.date().getDayOfYear()+row.card(),lunch.length)];
    }
    private record Purchase(long id,long card,long merchant,long amount,Timestamp at,String name) {}

    void apply(QaSeedSpec spec) throws Exception {
        require(spec.datasetKey().matches("qa-550-life-[123]") && spec.personas().size()==1
            && spec.baseDate().equals(QaLifeScenario.BASE),"Only the three fixed LIFE datasets are allowed");
        var person=spec.personas().get(0);
        require(person.scenario()==QaSeedSpec.Scenario.LIFE,"Only LIFE is allowed");
        var members=db.queryForList("SELECT user_id,persona_key FROM qa_seed_member WHERE dataset_key=? FOR UPDATE",spec.datasetKey());
        require(members.size()==1 && person.key().equals(members.get(0).get("persona_key")),"Seed ownership differs");
        long user=((Number)members.get(0).get("user_id")).longValue();
        require(db.queryForList("SELECT user_id FROM app_user WHERE user_id=? AND ssafy_member_id=? AND name=? AND phone_hash=? AND birth_date=? AND status IN ('ACTIVE','PENDING') FOR UPDATE",Long.class,
            user,person.ssafyMemberId(),person.name(),lookup.phone(person.phone()),person.birthDate()).size()==1,"Identity binding differs");
        var purchases=new ArrayList<Purchase>();
        var mapper=new ObjectMapper();int changed=0,refreshed=0;
        for(var purchase:QaLifeScenario.payments(spec.baseDate())) {
            String display=name(purchase);if(display==null) continue;
            String key=UUID.nameUUIDFromBytes((spec.datasetKey()+":"+person.key()+":"+purchase.card()+":"+purchase.date()+":"+purchase.slot()).getBytes(StandardCharsets.UTF_8)).toString();
            var rows=db.queryForList("""
                SELECT p.payment_transaction_id,p.user_card_id,p.merchant_id,p.merchant_context_snapshot,p.version,p.approved_at,m.merchant_name
                FROM payment_transaction p JOIN user_card c ON c.user_card_id=p.user_card_id AND c.user_id=p.user_id
                JOIN merchant m ON m.merchant_id=p.merchant_id
                WHERE p.user_id=? AND p.request_key=? AND c.card_no_hash=? AND m.brand_code=?
                  AND m.merchant_category_code='GENERAL_RESTAURANT' AND p.requested_amount=? AND p.approved_at=?
                  AND (p.source='SEED' OR p.merchant_context_snapshot->>'$.source'='QA_LIFE_550') FOR UPDATE
                """,user,key,lookup.card(person.cards().get(purchase.card()).cardNo()),purchase.brand(),purchase.amount(),
                Timestamp.from(purchase.date().atStartOfDay(KST).plusMinutes(purchase.slot()).toInstant()));
            require(rows.size()==1,"Original seed purchase differs; refusing overwrite");
            var row=rows.get(0);long id=((Number)row.get("payment_transaction_id")).longValue();
            ObjectNode context=(ObjectNode)mapper.readTree((String)row.get("merchant_context_snapshot"));
            String old=context.path("merchantName").isNull()?"":context.path("merchantName").asText("");
            require(old.isEmpty() || old.equals("야무 테스트 식당") || old.equals("느티나무식당") || NAMES.contains(old),"Edited display name; refusing overwrite");
            require(!context.has("displayNameSource") || context.path("displayNameSource").asText().equals("QA_DISPLAY_550_V1"),"Unknown display revision");
            if(!display.equals(old)) {
                context.put("merchantName",display);context.put("displayNameSource","QA_DISPLAY_550_V1");
                require(db.update("UPDATE payment_transaction SET merchant_context_snapshot=?,version=version+1,updated_at=? WHERE payment_transaction_id=? AND user_id=? AND version=?",
                    mapper.writeValueAsString(context),Timestamp.from(clock.instant()),id,user,row.get("version"))==1,"Purchase changed concurrently");
                changed++;
                String previous=old.isEmpty()?(String)row.get("merchant_name"):old;
                // Keep existing cancellation notices consistent too; preserve event identity/time/read state.
                db.update("UPDATE notification SET body=CONCAT(?,SUBSTRING(body,?)) WHERE user_id=? AND target_type='PAYMENT' AND target_id=? AND LEFT(body,?)=?",
                    display,previous.length()+1,user,id,previous.length()+1,previous+" ");
                SeedNotificationReplay.refreshPayment(db,user,id);refreshed++;
            }
            purchases.add(new Purchase(id,((Number)row.get("user_card_id")).longValue(),((Number)row.get("merchant_id")).longValue(),purchase.amount(),(Timestamp)row.get("approved_at"),display));
        }
        require(purchases.size()==196,"Original restaurant fixture count differs");
        int candidates=0,occurrences=0;
        for(var item:db.queryForList("""
            SELECT * FROM cashflow_item WHERE user_id=? AND source='DETECTED' AND status='CANDIDATE'
              AND version=0 AND detected_user_card_id IS NOT NULL AND detected_merchant_id IS NOT NULL AND detected_amount IS NOT NULL
              AND title IN ('야무 테스트 식당','느티나무식당') FOR UPDATE
            """,user)) {
            var latest=purchases.stream().filter(p->Objects.equals(p.card(),((Number)item.get("detected_user_card_id")).longValue())
                && Objects.equals(p.merchant(),((Number)item.get("detected_merchant_id")).longValue())
                && p.amount()==((Number)item.get("detected_amount")).longValue()).max(Comparator.comparing(Purchase::at));
            if(latest.isEmpty()) continue;
            long id=((Number)item.get("cashflow_item_id")).longValue();String old=(String)item.get("title");
            // Hold the same aggregate row lock as the editing service; keep the untouched-candidate version semantics.
            occurrences+=db.update("UPDATE cashflow_occurrence SET title=? WHERE cashflow_item_id=? AND user_id=? AND title=? AND is_overridden=FALSE AND version=0",
                latest.get().name(),id,user,old);
            candidates+=db.update("UPDATE cashflow_item SET title=? WHERE cashflow_item_id=? AND user_id=? AND version=0 AND title=?",latest.get().name(),id,user,old);
        }
        int cafe=0;
        var cafes=db.queryForList("SELECT merchant_id,merchant_name FROM merchant WHERE brand_code='YAMU_TEST_CAFE' FOR UPDATE");
        if(cafes.size()==1 && Set.of("야무 테스트 카페","오월커피").contains(cafes.get(0).get("merchant_name"))) {
            Object id=cafes.get(0).get("merchant_id");
            // Global catalog label only if absolutely no existing transaction (of any user) can be relabeled.
            require(db.queryForList("SELECT payment_transaction_id FROM payment_transaction WHERE merchant_id=? FOR UPDATE",Long.class,id).isEmpty(),"Cafe has existing purchases; preserve shared label");
            cafe=db.update("UPDATE merchant SET merchant_name='메가MGC커피',updated_at=? WHERE merchant_id=? AND merchant_name=?",Timestamp.from(clock.instant()),id,cafes.get(0).get("merchant_name"));
        }
        System.out.printf("LIFE_DISPLAY user=%d changedPurchases=%d refreshedPayments=%d candidates=%d occurrences=%d unusedCafeLabels=%d%n",user,changed,refreshed,candidates,occurrences,cafe);
    }
}
