import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yamu.be.global.crypto.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import static java.util.Map.entry;

/** Explicit offline DB writer. No application boot, Flyway, scheduler, delete, or bank mutation. */
public final class YoonseoSeed {
    static final ObjectMapper JSON=new ObjectMapper();
    static final ZoneId KST=ZoneId.of("Asia/Seoul");
    static final Set<String> FIELDS=Set.of("datasetKey","demoDate","historyThrough","name","birthDate","phone",
            "ssafyMemberId","ssafyUserKey","accountNo","pin","noriCardNo","zeroCardNo");
    final JsonNode input;
    final JdbcTemplate db;
    final PersonalDataCipher cipher;
    final LookupHasher lookup;
    final PinHasher pins;
    final byte[] fingerprint;
    final YoonseoPlan.Plan plan;
    final LocalDate through;
    final Timestamp now=Timestamp.from(Instant.now());
    long user,account,nori,zero,noriProduct,zeroProduct,noriBenefit,zeroBenefit,monthlyGroup,perTransactionGroup;
    record BankRow(String id,LocalDate date,LocalTime time,String direction,long amount,long balance) {}
    record BankProof(LocalDate date,List<BankRow> rows) {}

    public static void main(String[] args) {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        try {
            require(args.length==2 && Set.of("plan","check","apply","verify").contains(args[0]),"Use plan/check/apply/verify and private input file");
            JsonNode input=read(Path.of(args[1]));
            var through=LocalDate.parse(input.path("historyThrough").asText());
            var plan=YoonseoPlan.build(through);
            printPlan(plan,through);
            if(args[0].equals("plan"))return;
            validate(input,true);
            int id=Integer.parseInt(env("YAMU_CRYPTO_DATA_KEY_ID"));
            var keys=new CryptoKeys(new CryptoProperties(id,Map.of(id,env("YAMU_CRYPTO_DATA_KEY_"+id)),
                    env("YAMU_CRYPTO_HMAC_KEY"),env("YAMU_CRYPTO_PIN_PEPPER")));
            String url=env("YOONSEO_JDBC_URL");
            require(url.startsWith("jdbc:mysql://") && url.contains("serverTimezone=UTC"),"MySQL UTC JDBC URL required");
            try(var c=DriverManager.getConnection(url,env("YOONSEO_DB_USERNAME"),env("YOONSEO_DB_PASSWORD"))) {
                try(var s=c.createStatement()){s.execute("SET time_zone='+00:00'");}
                var seed=new YoonseoSeed(c,input,keys,Base64.getDecoder().decode(env("YAMU_CRYPTO_HMAC_KEY")));
                seed.schema();seed.catalog();
                if(args[0].equals("verify")){seed.existing();seed.verify();return;}
                if(seed.db.queryForObject("SELECT COUNT(*) FROM qa_seed_dataset WHERE dataset_key=?",Integer.class,seed.text("datasetKey"))>0){
                    seed.existing();seed.verify();System.out.println("UNCHANGED: owned seed already installed; bank not touched");return;
                }
                // Binding and balance are checked using read-only SSAFY calls before taking a DB lock.
                var proof=bankProof(input,through);
                if(args[0].equals("check")){seed.checkIdentity();System.out.println("CHECK OK: schema, catalog, identity, bank ownership, 800000 balance and funding boundary");return;}
                require("true".equals(env("YOONSEO_SEED_MAINTENANCE")),"Pause this demo user's traffic and workers before apply");
                require(env("YOONSEO_EXPECTED_DATABASE").equals(seed.db.queryForObject("SELECT DATABASE()",String.class)),"Wrong target database");
                seed.apply(c,proof);
            }
        } catch(Exception e) {
            // Never print SQLException/Jackson/HTTP exception messages: they can contain credentials or input.
            System.err.println("YOONSEO FAILED: "+e.getClass().getSimpleName()+
                    (e instanceof YoonseoPlan.SeedFailure?" / "+e.getMessage():" / inspect prerequisites; no secret-bearing stack trace emitted"));
            System.exit(1);
        }
    }
    YoonseoSeed(Connection c,JsonNode input,CryptoKeys keys,byte[] hmacKey)throws Exception {
        this.input=input;db=new JdbcTemplate(new SingleConnectionDataSource(c,true));
        cipher=new PersonalDataCipher(keys,new SecureRandom());lookup=new LookupHasher(keys);pins=new PinHasher(keys);
        through=LocalDate.parse(text("historyThrough"));plan=YoonseoPlan.build(through);
        var sorted=new TreeMap<String,String>();FIELDS.forEach(k->sorted.put(k,input.path(k).asText()));
        var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(hmacKey,"HmacSHA256"));
        fingerprint=mac.doFinal(JSON.writeValueAsBytes(sorted));
    }
    static JsonNode read(Path path)throws Exception {var i=JSON.readTree(Files.readString(path));validate(i,false);return i;}
    static void validate(JsonNode i,boolean bound) {
        require(i!=null&&i.isObject(),"Object input required");
        var fields=new HashSet<String>();i.fieldNames().forEachRemaining(fields::add);require(fields.equals(FIELDS),"Missing or unknown input fields");
        require(i.path("datasetKey").asText().matches("yoonseo-[a-z0-9-]{1,48}"),"Invalid dataset key");
        require(i.path("demoDate").asText().equals("2026-11-18"),"This scenario is fixed to 2026-11-18");
        require(i.path("name").asText().equals("이윤서")&&i.path("birthDate").asText().equals("1999-11-18"),"Wrong persona identity");
        require(i.path("phone").asText().matches("010[0-9]{8}"),"Invalid synthetic phone");
        var through=LocalDate.parse(i.path("historyThrough").asText());YoonseoPlan.build(through);
        if(bound){
            require(through.isBefore(LocalDate.now(KST)),"History must precede today; never create future approved purchases");
            require(i.path("pin").asText().matches("[0-9]{6}"),"Private six-digit PIN required");
            for(String key:List.of("noriCardNo","zeroCardNo"))require(i.path(key).asText().matches("[0-9]{16}"),"Private unique card numbers required");
            require(!i.path("noriCardNo").equals(i.path("zeroCardNo")),"Cards must differ");
            require(i.path("accountNo").asText().matches("[0-9]{8,30}"),"SSAFY account binding required");
            require(i.path("ssafyMemberId").asText().matches("y[0-9]{9}@yamu.app"),"SSAFY member binding required");
            require(!i.path("ssafyUserKey").asText().contains("FILL")&&!i.path("ssafyUserKey").asText().isBlank(),"SSAFY private user key required");
        }
    }
    static void printPlan(YoonseoPlan.Plan p,LocalDate through) {
        System.out.printf("PLAN base=2026-11-18 historyThrough=%s payments=%d accountRows=%d opening=%d closing=%d%n",
                through,p.payments().size(),p.bankRows().size(),p.opening(),p.closing());
        System.out.println("November benefits before demo: LOCA confirmed 25200 + Nori pending 5000 + ZERO pending 300 = 30500. Four additional settled large purchases; LOCA inactive.");
        System.out.println("Starbucks 20000: expected NORI=4000 ZERO=160. November bill net=100000; monthly Nori remaining=15000.");
    }
    String text(String key){return input.path(key).asText();}
    static String env(String name){String value=System.getenv(name);require(value!=null&&!value.isBlank(),"Missing environment: "+name);return value;}
    static void require(boolean ok,String message){YoonseoPlan.require(ok,message);}
    static String hash(String value)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
    static Timestamp at(LocalDate date,int hour){return Timestamp.from(date.atTime(hour,0).atZone(KST).toInstant());}
    static Map<String,Object> row(Object... pairs){var map=new LinkedHashMap<String,Object>();for(int i=0;i<pairs.length;i+=2)map.put((String)pairs[i],pairs[i+1]);return map;}
    long insert(String table,Map<String,Object> row){
        String sql="INSERT INTO "+table+" ("+String.join(",",row.keySet())+") VALUES ("+String.join(",",Collections.nCopies(row.size(),"?"))+")";
        db.update(sql,row.values().toArray());return db.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
    }
    Map<String,Object> timed(Object... pairs){var r=row(pairs);r.put("created_at",now);r.put("updated_at",now);return r;}
    void schema(){
        Map<String,List<String>> tables=Map.ofEntries(
                entry("qa_seed_dataset",List.of("dataset_key","input_hash")),entry("qa_seed_member",List.of("user_id","enriched")),
                entry("payment_transaction",List.of("recommendation_result_json","recommended_rank","user_selected","spending_class")),
                entry("payment_benefit",List.of("krw_amount","confirmation_source","confirmed_at")),
                entry("account_history_stream",List.of("dataset_key","input_hash")),entry("account_history_coverage",List.of("opening_evidence")),
                entry("account_transaction",List.of("content_hash")),entry("qa_seed_funding_boundary",List.of("funded_balance")),
                entry("bill_item_discount",List.of("amount")));
        tables.forEach((t,cols)->cols.forEach(col->require(db.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=? AND column_name=?",Integer.class,t,col)==1,"Target schema missing "+t+"."+col)));
    }
    long product(String name){var ids=db.queryForList("SELECT card_product_id FROM card_product WHERE product_name=?",Long.class,name);require(ids.size()==1,"Missing or ambiguous card product");return ids.get(0);}
    void catalog(){
        noriProduct=product("KB국민 노리 체크");zeroProduct=product("현대카드 ZERO Edition3 (할인형)");
        noriBenefit=benefit(noriProduct,"스타벅스 환급할인","CASHBACK","AFTER_BILLING",0.2);
        zeroBenefit=benefit(zeroProduct,"기본 청구 할인","DISCOUNT","AT_BILLING",0.008);
        monthlyGroup=group("MONTHLY");perTransactionGroup=group("PER_TRANSACTION");
        require(limit(monthlyGroup,400000)==20000&&limit(perTransactionGroup,400000)==4000,"Catalog limit differs from demo design");
        for(String brand:plan.payments().stream().map(YoonseoPlan.Payment::brand).distinct().toList())merchant(brand);
    }
    long benefit(long product,String name,String type,String timing,double rate){
        var rows=db.queryForList("SELECT card_benefit_id,rate,benefit_type,application_timing FROM card_benefit WHERE card_product_id=? AND benefit_name=? AND policy_version=1",product,name);
        require(rows.size()==1,"Missing benefit policy version 1");var r=rows.get(0);
        require(type.equals(r.get("benefit_type"))&&timing.equals(r.get("application_timing"))&&Math.abs(((Number)r.get("rate")).doubleValue()-rate)<0.0000001,"Catalog benefit differs from demo design");
        return ((Number)r.get("card_benefit_id")).longValue();
    }
    long group(String period){var ids=db.queryForList("SELECT g.limit_group_id FROM limit_group g JOIN benefit_limit_link l USING(limit_group_id) WHERE l.card_benefit_id=? AND g.period_type=? AND g.limit_unit='KRW'",Long.class,noriBenefit,period);require(ids.size()==1,"Missing expected Nori limit group");return ids.get(0);}
    long limit(long group,long performance){return db.queryForObject("SELECT limit_value FROM tier_limit WHERE limit_group_id=? AND min_performance_amount<=? AND (max_performance_amount IS NULL OR max_performance_amount>?)",Long.class,group,performance,performance);}
    Map<String,Object> merchant(String brand){var rows=db.queryForList("SELECT merchant_id,merchant_name,merchant_category_code FROM merchant WHERE brand_code=?",brand);require(rows.size()==1,"Missing or ambiguous merchant: "+brand);return rows.get(0);}
    void checkIdentity(){
        var own=db.queryForList("SELECT user_id FROM qa_seed_member WHERE dataset_key=? AND persona_key='yoonseo'",Long.class,text("datasetKey"));
        if(!own.isEmpty()){existing();return;}
        require(db.queryForObject("SELECT COUNT(*) FROM app_user WHERE (phone_hash=? AND birth_date=?) OR ssafy_member_id=?",Integer.class,lookup.phone(text("phone")),LocalDate.parse(text("birthDate")),text("ssafyMemberId"))==0,"Existing user is not owned by this dataset; never adopt or overwrite");
        require(db.queryForObject("SELECT COUNT(*) FROM linked_account WHERE account_no_hash=?",Integer.class,lookup.account("004",text("accountNo")))==0,"Account already linked in target database");
        for(String key:List.of("noriCardNo","zeroCardNo"))require(db.queryForObject("SELECT COUNT(*) FROM user_card WHERE card_no_hash=?",Integer.class,lookup.card(text(key)))==0,"Card number already present");
    }
    void existing(){
        var hashes=db.queryForList("SELECT input_hash FROM qa_seed_dataset WHERE dataset_key=?",byte[].class,text("datasetKey"));
        require(hashes.size()==1&&MessageDigest.isEqual(hashes.get(0),fingerprint),"Dataset input changed or missing; no automatic overwrite");
        user=db.queryForObject("SELECT user_id FROM qa_seed_member WHERE dataset_key=? AND persona_key='yoonseo' AND enriched=TRUE",Long.class,text("datasetKey"));
        account=db.queryForObject("SELECT linked_account_id FROM linked_account WHERE user_id=? AND account_no_hash=?",Long.class,user,lookup.account("004",text("accountNo")));
        nori=db.queryForObject("SELECT user_card_id FROM user_card WHERE user_id=? AND card_product_id=?",Long.class,user,noriProduct);
        zero=db.queryForObject("SELECT user_card_id FROM user_card WHERE user_id=? AND card_product_id=?",Long.class,user,zeroProduct);
        require(cipher.decrypt(db.queryForObject("SELECT phone_enc FROM app_user WHERE user_id=?",byte[].class,user),CipherField.APP_USER_PHONE).equals(text("phone")),"Target encryption key mismatch");
    }
    void apply(Connection c,BankProof proof)throws Exception {
        String lock="yoonseo-seed:"+text("datasetKey");
        require(Objects.equals(db.queryForObject("SELECT GET_LOCK(?,10)",Integer.class,lock),1),"Another seed operation is running");
        try {
            c.setAutoCommit(false);checkIdentity();
            if(db.queryForObject("SELECT COUNT(*) FROM qa_seed_dataset WHERE dataset_key=?",Integer.class,text("datasetKey"))>0){existing();verify();c.rollback();System.out.println("UNCHANGED: owned seed already installed");return;}
            insert("qa_seed_dataset",row("dataset_key",text("datasetKey"),"seed_version",1,"base_date",YoonseoPlan.DEMO,"input_hash",fingerprint,"run_id",UUID.randomUUID().toString(),"created_at",now));
            createIdentity();createPayments();YoonseoRewards.create(this);createCashflows();createHistory(proof);verify();
            c.commit();System.out.printf("APPLIED user=%d account=%d cards=%d,%d; no financial mutations%n",user,account,nori,zero);
        }catch(Exception e){c.rollback();throw e;}finally{c.setAutoCommit(true);db.queryForObject("SELECT RELEASE_LOCK(?)",Integer.class,lock);}
    }
    void createIdentity()throws Exception {
        user=insert("app_user",timed("name",text("name"),"birth_date",LocalDate.parse(text("birthDate")),"phone_enc",cipher.encrypt(text("phone"),CipherField.APP_USER_PHONE),"phone_hash",lookup.phone(text("phone")),"pin_hash",pins.hash(text("pin")),"pin_updated_at",now,"identity_verified_at",now,"ssafy_member_id",text("ssafyMemberId"),"ssafy_user_key",cipher.encrypt(text("ssafyUserKey"),CipherField.APP_USER_SSAFY_USER_KEY),"status","ACTIVE"));
        insert("qa_seed_member",row("dataset_key",text("datasetKey"),"persona_key","yoonseo","user_id",user,"enriched",true));
        String a=text("accountNo");
        account=insert("linked_account",timed("user_id",user,"bank_code","004","account_no_enc",cipher.encrypt(a,CipherField.LINKED_ACCOUNT_NO),"account_no_hash",lookup.account("004",a),"account_name","국민은행 입출금","account_alias","급여·생활비 계좌","account_last4",a.substring(a.length()-4),"status","CONNECTED","is_primary",true,"connected_at",at(LocalDate.of(2026,7,31),9)));
        for(boolean isNori:List.of(true,false)){
            String number=text(isNori?"noriCardNo":"zeroCardNo");long product=isNori?noriProduct:zeroProduct;
            long card=insert("user_card",timed("user_id",user,"card_product_id",product,"linked_account_id",account,"issuer_account_bank_code","004","issuer_account_hash",lookup.account("004",a),"issuer_account_last4",a.substring(a.length()-4),"card_no_enc",cipher.encrypt(number,CipherField.USER_CARD_NO),"card_no_hash",lookup.card(number),"card_last4",number.substring(12),"card_alias",isNori?"노리 · 일상 혜택":"ZERO · 기본 할인","payment_day",isNori?null:23,"status","ACTIVE","issuer_status","NORMAL","registered_at",at(LocalDate.of(2026,7,31),9),"issuer_registered_on",LocalDate.of(2025,1,1)));
            if(isNori)nori=card;else zero=card;
        }
    }
    String requestKey(int index){return UUID.nameUUIDFromBytes((text("datasetKey")+":payment:"+index).getBytes(StandardCharsets.UTF_8)).toString();}
    void createPayments()throws Exception {
        long bill=insert("card_bill",timed("user_id",user,"user_card_id",zero,"linked_account_id",account,"billing_month",LocalDate.of(2026,11,1),"period_start",LocalDate.of(2026,10,10),"period_end",LocalDate.of(2026,11,9),"withdrawal_date",LocalDate.of(2026,11,23),"status","CONFIRMED","confirmed_amount",100000,"confirmed_at",at(LocalDate.of(2026,11,10),9)));
        long nextBill=insert("card_bill",timed("user_id",user,"user_card_id",zero,"linked_account_id",account,"billing_month",LocalDate.of(2026,12,1),"period_start",LocalDate.of(2026,11,10),"period_end",LocalDate.of(2026,12,9),"withdrawal_date",LocalDate.of(2026,12,23),"status","ACCUMULATING"));
        var monthlyUsed=new HashMap<LocalDate,Long>();int index=0;
        for(var p:plan.payments()){
            boolean isNori=p.card().equals("NORI");long card=isNori?nori:zero,product=isNori?noriProduct:zeroProduct;
            var merchant=merchant(p.brand());var date=at(p.day(),isNori?12:15);String key=requestKey(index++);
            var snapshot=JSON.createObjectNode().put("source","YOONSEO_DEMO_V1").put("synthetic",true).put("brandCode",p.brand()).put("merchantName",(String)merchant.get("merchant_name")).put("categoryCode",(String)merchant.get("merchant_category_code"));
            var values=timed("user_id",user,"user_card_id",card,"card_product_id",product,"merchant_id",merchant.get("merchant_id"),"payment_channel",p.brand().equals("COUPANG")||p.brand().equals("BAEMIN")?"ONLINE":"OFFLINE","facility_type","GENERAL","merchant_context_snapshot",JSON.writeValueAsString(snapshot),"request_key",key,"request_fingerprint",MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)),"source",p.source(),"status","APPROVED","requested_amount",p.amount(),"approved_amount",p.amount(),"installment_months",1,"recognized_performance_amount",p.amount(),"performance_basis_amount",isNori?previousPerformance(p.day()):0,"performance_month",p.day().withDayOfMonth(1),"requested_at",date,"approved_at",date,"spending_class","LIVING","spending_class_source","AUTO");
            if(isNori&&p.brand().equals("STARBUCKS")){
                values.put("recommendation_result_json",recommendation(p));values.put("recommended_rank",1);values.put("user_selected",false);
            }
            long payment=insert("payment_transaction",values);long benefit=0;
            if(p.benefit()>0)benefit=insert("payment_benefit",row("payment_transaction_id",payment,"card_product_id",product,"card_benefit_id",isNori?noriBenefit:zeroBenefit,"benefit_value",p.benefit(),"value_unit","KRW","krw_amount",p.benefit(),"created_at",date));
            if(isNori&&p.benefit()>0){
                var month=p.day().withDayOfMonth(1);long used=monthlyUsed.getOrDefault(month,0L);
                for(long group:List.of(monthlyGroup,perTransactionGroup))insert("payment_limit_usage",timed("payment_transaction_id",payment,"card_product_id",product,"limit_group_id",group,"remaining_before",limit(group,previousPerformance(p.day()))-(group==monthlyGroup?used:0),"consumed_value",p.benefit(),"usage_date",p.day(),"usage_month",month));
                monthlyUsed.put(month,used+p.benefit());
            }
            if(!isNori){boolean confirmed=p.day().getMonthValue()==10;
                long item=insert("bill_item",timed("payment_transaction_id",payment,"user_id",user,"user_card_id",zero,"card_bill_id",confirmed?bill:nextBill,"installment_no",1,"billing_month",LocalDate.of(2026,confirmed?11:12,1),"principal_amount",p.amount(),"fee_amount",0,"billed_amount",p.amount(),"status",confirmed?"CONFIRMED":"PENDING"));
                if(confirmed)insert("bill_item_discount",row("card_bill_id",bill,"bill_item_id",item,"payment_benefit_id",benefit,"amount",p.benefit(),"created_at",at(LocalDate.of(2026,11,10),9)));
            }
        }
        for(var e:monthlyUsed.entrySet())insert("monthly_limit_usage",timed("user_card_id",nori,"card_product_id",noriProduct,"limit_group_id",monthlyGroup,"usage_month",e.getKey(),"used_value",e.getValue()));
        for(long card:List.of(nori,zero))for(int m=7;m<=11;m++){
            final int month=m;String kind=card==nori?"NORI":"ZERO";
            long amount=plan.payments().stream().filter(p->p.card().equals(kind)&&p.day().getMonthValue()==month).mapToLong(YoonseoPlan.Payment::amount).sum();
            insert("card_monthly_performance",timed("user_card_id",card,"performance_month",LocalDate.of(2026,m,1),"recognized_amount",amount));
        }
    }
    long previousPerformance(LocalDate date){int m=date.minusMonths(1).getMonthValue();return m==8||m==9?490000:400000;}
    String recommendation(YoonseoPlan.Payment p)throws Exception {
        var result=JSON.createObjectNode().put("recommendationId","rec_yoonseo_"+p.day()).put("calculatedAt",p.day().atTime(3,0).toString()).put("estimate",false).put("schemaVersion",1);
        for(String key:List.of("excludedCards","uncomparedCards","issues","simulationRules"))result.putArray(key);
        var candidates=result.putArray("candidates");
        for(boolean first:List.of(true,false)){
            long value=first?p.benefit():p.amount()*8/1000;long bid=first?noriBenefit:zeroBenefit;long product=first?noriProduct:zeroProduct;
            var c=candidates.addObject().put("rank",first?1:2).put("netBenefit",value).put("reason","시연용 과거 거래: 스타벅스 혜택 비교");
            if(first)c.put("selectionReason","MAXIMUM_NET_BENEFIT");else c.putNull("selectionReason");
            c.putObject("card").put("userCardId",first?nori:zero).put("cardProductId",product).put("cardType",first?"CHECK":"CREDIT").put("defaultCard",false).put("alias",first?"노리 · 일상 혜택":"ZERO · 기본 할인").put("last4",text(first?"noriCardNo":"zeroCardNo").substring(12)).put("issuerCode",first?"KB":"HYUNDAI").put("productName",first?"KB국민 노리 체크":"현대카드 ZERO Edition3 (할인형)");
            c.putArray("benefits").addObject().put("benefitId",bid).put("name",first?"스타벅스 환급할인":"기본 청구 할인").put("type",first?"CASHBACK":"DISCOUNT").put("rawAmount",value).put("amount",value).put("realization",first?"AFTER_BILLING":"AT_BILLING");
            c.putObject("ruleVersion").put("cardProductId",product).put("version",1).putObject("benefitVersions").put(Long.toString(bid),1);
            c.putArray("unallocatedBenefitGroups");c.putArray("expectedLimitUses");
        }
        return JSON.writeValueAsString(result);
    }
    void createCashflows()throws Exception {
        long income=0;
        for(var flow:List.of(row("title","월세","direction","EXPENSE","amount",400000L,"day",20),row("title","통신비","direction","EXPENSE","amount",70000L,"day",22),row("title","급여","direction","INCOME","amount",2500000L,"day",25))){
            int day=(int)flow.get("day");var date=LocalDate.of(2026,11,day);
            long item=insert("cashflow_item",timed("user_id",user,"linked_account_id",account,"title",flow.get("title"),"direction",flow.get("direction"),"amount",flow.get("amount"),"scheduled_date",date,"recurrence_type","MONTHLY","recurrence_day_of_month",day,"status","CONFIRMED","memo","이윤서 발표 시연용 합성 일정"));
            for(int offset=0;offset<3;offset++)insert("cashflow_occurrence",timed("cashflow_item_id",item,"user_id",user,"linked_account_id",account,"occurrence_date",date.plusMonths(offset),"scheduled_date",date.plusMonths(offset),"title",flow.get("title"),"direction",flow.get("direction"),"amount",flow.get("amount"),"status","CONFIRMED","is_overridden",false));
            if(day==25)income=item;
        }
        var settings=JSON.createObjectNode().put("horizon","DAYS").put("defaultDays",30).put("alertThreshold",100000).put("incomeCashflowItemId",income).put("dailyLivingCostTarget",15000).put("includeCandidates",false).put("conservativeLine",true);
        db.update("UPDATE app_user SET forecast_settings=? WHERE user_id=?",JSON.writeValueAsString(settings),user);
    }
    void createHistory(BankProof proof)throws Exception {
        // Actual funding is imported verbatim; historical reconstructed money is explicitly QA_SYNTHETIC.
        long actual=insert("account_history_stream",timed("linked_account_id",account,"user_id",user,"source","SSAFY","generation",1,"active",true,"range_from",proof.date(),"range_to",proof.date()));
        long funding=0;String fundingHash=null;
        for(var r:proof.rows()){
            String h=hash(r.date()+"|"+r.time()+"|"+r.direction()+"|"+r.amount()+"|"+r.balance());
            long id=insert("account_transaction",row("stream_id",actual,"external_id",r.id(),"occurred_at",Timestamp.from(r.date().atTime(r.time()).atZone(KST).toInstant()),"direction",r.direction(),"amount",r.amount(),"balance_after",r.balance(),"content_hash",h,"created_at",now));
            if(funding==0){funding=id;fundingHash=h;}
        }
        long actualRun=historyRun(actual,proof.date(),proof.date(),proof.rows().size());
        insert("account_history_coverage",timed("stream_id",actual,"run_id",actualRun,"range_from",proof.date(),"range_to",proof.date(),"valid",true,"opening_balance",0,"opening_evidence","FIRST_TRANSACTION","opening_transaction_id",funding));
        long synthetic=insert("account_history_stream",timed("linked_account_id",account,"user_id",user,"source","QA_SYNTHETIC","dataset_key",text("datasetKey"),"generation",1,"active",false,"range_from",YoonseoPlan.FROM,"range_to",through,"input_hash",HexFormat.of().formatHex(fingerprint)));
        int i=0;for(var row:plan.bankRows()){
            var m=row.movement();String direction=m.signedAmount()>0?"DEPOSIT":"WITHDRAWAL";long amount=Math.abs(m.signedAmount());
            insert("account_transaction",row("stream_id",synthetic,"external_id",String.format(Locale.ROOT,"YOONSEO-%04d",i++),"occurred_at",Timestamp.from(m.day().atTime(m.time()).atZone(KST).toInstant()),"direction",direction,"amount",amount,"balance_after",row.balance(),"content_hash",hash(m.day()+"|"+m.time()+"|"+direction+"|"+amount+"|"+row.balance()),"created_at",now));
        }
        long run=historyRun(synthetic,YoonseoPlan.FROM,through,plan.bankRows().size());
        insert("account_history_coverage",timed("stream_id",synthetic,"run_id",run,"range_from",YoonseoPlan.FROM,"range_to",through,"valid",true,"opening_balance",plan.opening(),"opening_evidence","QA_SCENARIO"));
        insert("qa_seed_funding_boundary",row("qa_stream_id",synthetic,"actual_stream_id",actual,"funding_transaction_id",funding,"funding_content_hash",fundingHash,"funded_balance",800000));
        db.update("UPDATE account_history_stream SET active=TRUE,version=version+1 WHERE stream_id=?",synthetic);
    }
    long historyRun(long stream,LocalDate from,LocalDate to,int count){return insert("account_history_sync_run",timed("stream_id",stream,"generation",1,"range_from",from,"range_to",to,"status","OK","reported_count",count,"received_count",count,"queried_at",now));}
    void verify(){
        require(db.queryForObject("SELECT COUNT(*) FROM payment_transaction WHERE user_id=? AND merchant_context_snapshot->>'$.source'='YOONSEO_DEMO_V1'",Integer.class,user)==plan.payments().size(),"Fixture payment count mismatch");
        for(int month=8;month<=10;month++)require(db.queryForObject("SELECT recognized_amount FROM card_monthly_performance WHERE user_card_id=? AND performance_month=?",Long.class,nori,LocalDate.of(2026,month,1))==(month==10?400000L:490000L),"Previous performance mismatch");
        require(db.queryForObject("SELECT confirmed_amount FROM card_bill WHERE user_id=? AND user_card_id=? AND billing_month='2026-11-01'",Long.class,user,zero)==100000,"November bill mismatch");
        require(db.queryForObject("SELECT SUM(i.billed_amount)-SUM(d.amount) FROM bill_item i JOIN bill_item_discount d USING(bill_item_id) WHERE i.user_id=? AND i.user_card_id=?",Long.class,user,zero)==100000,"Billing discount reconciliation failed");
        require(db.queryForObject("SELECT SUM(b.krw_amount) FROM payment_benefit b JOIN payment_transaction p USING(payment_transaction_id) WHERE p.user_id=? AND p.requested_at>=? AND p.merchant_context_snapshot->>'$.source'='YOONSEO_DEMO_V1'",Long.class,user,at(LocalDate.of(2026,11,1),0))==5300,"November benefit summary mismatch");
        long stream=db.queryForObject("SELECT stream_id FROM account_history_stream WHERE user_id=? AND source='QA_SYNTHETIC' AND active=TRUE",Long.class,user);
        var rows=db.queryForList("SELECT amount,direction,balance_after FROM account_transaction WHERE stream_id=? ORDER BY occurred_at,external_id",stream);
        require(rows.size()==plan.bankRows().size(),"Account history count mismatch");long b=plan.opening();
        for(var row:rows){b+=((Number)row.get("amount")).longValue()*(row.get("direction").equals("DEPOSIT")?1:-1);require(b==((Number)row.get("balance_after")).longValue(),"Account running balance mismatch");}
        require(b==800000,"Account closing mismatch");
        require(db.queryForObject("SELECT COUNT(*) FROM cashflow_occurrence WHERE user_id=? AND scheduled_date BETWEEN '2026-11-18' AND '2026-11-25'",Integer.class,user)==3,"Forecast schedule mismatch");
        require(db.queryForObject("SELECT COUNT(*) FROM monthly_limit_usage m WHERE m.user_card_id=? AND m.limit_group_id=? AND m.used_value<>(SELECT COALESCE(SUM(p.consumed_value),0) FROM payment_limit_usage p JOIN payment_transaction t USING(payment_transaction_id) WHERE t.user_card_id=m.user_card_id AND p.limit_group_id=m.limit_group_id AND p.usage_month=m.usage_month AND p.restored_at IS NULL)",Integer.class,nori,monthlyGroup)==0,"Monthly limit usage does not match consumption evidence");
        int i=0;for(var p:plan.payments()) {
            require(db.queryForObject("SELECT COUNT(*) FROM payment_transaction WHERE user_id=? AND request_key=? AND requested_amount=? AND approved_amount=? AND user_card_id=? AND performance_month=? AND status='APPROVED'",Integer.class,user,requestKey(i++),p.amount(),p.amount(),p.card().equals("NORI")?nori:zero,p.day().withDayOfMonth(1))==1,"Fixture payment changed");
        }
        System.out.printf("VERIFY OK user=%d payments=%d history=%d closing=800000 NovemberBenefits=5300 bill=100000%n",user,plan.payments().size(),rows.size());
    }
    static BankProof bankProof(JsonNode input,LocalDate through)throws Exception {
        var bank=new ReadOnlyBank(input.path("ssafyUserKey").asText());String account=input.path("accountNo").asText();
        var member=bank.member(input.path("ssafyMemberId").asText());require(member.path("userKey").asText().equals(input.path("ssafyUserKey").asText()),"Bank member binding mismatch");
        var accounts=bank.call("inquireDemandDepositAccountList",Map.of());boolean found=false;
        for(var a:accounts)if(a.path("accountNo").asText().equals(account)&&a.path("bankCode").asText().equals("004"))found=true;
        require(found,"KB account is not owned by supplied SSAFY member");
        var balance=bank.call("inquireDemandDepositAccountBalance",Map.of("accountNo",account));
        require(balance.path("accountBalance").asLong(-1)==800000,"Bank balance must be 800000; no automatic funding or reset");
        LocalDate day=through.plusDays(1);require(day.equals(LocalDate.now(KST)),"Apply on the funding day immediately after historyThrough");
        var history=bank.call("inquireTransactionHistoryList",Map.of("accountNo",account,"startDate",day.format(DateTimeFormatter.BASIC_ISO_DATE),"endDate",day.format(DateTimeFormatter.BASIC_ISO_DATE),"transactionType","A","orderByType","ASC"));
        var list=history.path("list");require(list.isArray()&&list.size()==history.path("totalCount").asInt(-1)&&list.size()==1,"Require a fresh account with exactly one verified funding transaction today");
        var r=list.get(0);require(r.path("transactionType").asText().equals("1")&&r.path("transactionBalance").asLong()==800000&&r.path("transactionAfterBalance").asLong()==800000,"Funding transaction mismatch");
        var date=LocalDate.parse(r.path("transactionDate").asText(),DateTimeFormatter.BASIC_ISO_DATE);require(date.equals(day),"Funding date mismatch");
        var row=new BankRow(r.path("transactionUniqueNo").asText(),date,LocalTime.parse(r.path("transactionTime").asText(),DateTimeFormatter.ofPattern("HHmmss")),"DEPOSIT",800000,800000);
        require(row.id().matches("[!-~]{1,128}"),"Invalid bank transaction identifier");
        return new BankProof(day,List.of(row));
    }
    /** Read-only whitelist; this executable cannot debit, credit or create bank objects. */
    static final class ReadOnlyBank {
        final String key;final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        ReadOnlyBank(String key){this.key=key;}
        JsonNode member(String id)throws Exception{return send("/member/search",JSON.valueToTree(Map.of("apiKey",env("SSAFY_API_KEY"),"userId",id)));}
        JsonNode call(String name,Map<String,String> body)throws Exception{
            require(Set.of("inquireDemandDepositAccountList","inquireDemandDepositAccountBalance","inquireTransactionHistoryList").contains(name),"Read-only financial endpoint required");
            String tx=LocalDateTime.now(KST).format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))+String.format(Locale.ROOT,"%06d",new SecureRandom().nextInt(1000000));
            ObjectNode data=JSON.valueToTree(body);var h=data.putObject("Header");
            h.put("apiName",name).put("transmissionDate",tx.substring(0,8)).put("transmissionTime",tx.substring(8,14)).put("institutionCode","00100").put("fintechAppNo","001").put("apiServiceCode",name).put("institutionTransactionUniqueNo",tx).put("apiKey",env("SSAFY_API_KEY")).put("userKey",key);
            var response=send("/edu/demandDeposit/"+name,data);require(response.path("Header").path("responseCode").asText().equals("H0000")&&response.hasNonNull("REC"),"Bank read unsuccessful or incomplete");return response.get("REC");
        }
        JsonNode send(String path,JsonNode data)throws Exception{
            String base=env("SSAFY_API_BASE_URL");URI uri=URI.create(base);require(uri.getScheme().equals("https")&&uri.getHost().equals("finopenapi.ssafy.io"),"Use the configured SSAFY HTTPS host");
            var request=HttpRequest.newBuilder(URI.create(base.replaceAll("/$","")+path)).timeout(Duration.ofSeconds(15)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(data))).build();
            var response=http.send(request,HttpResponse.BodyHandlers.ofString());require(response.statusCode()>=200&&response.statusCode()<300,"Bank HTTP request failed");return JSON.readTree(response.body());
        }
    }
}
