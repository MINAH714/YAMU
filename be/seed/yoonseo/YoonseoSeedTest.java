import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.yamu.be.global.crypto.*;
import com.yamu.be.payment.recommend.RecommendationResult;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Explicit tests: integration refuses any DB whose name does not start with yoonseo_test_. */
public final class YoonseoSeedTest {
    static int checks;
    static void check(boolean b,String message){checks++;if(!b)throw new AssertionError(message);}
    static void reject(Runnable action){boolean failed=false;try{action.run();}catch(YoonseoPlan.SeedFailure e){failed=true;}check(failed,"Must reject unsafe input");}
    public static void main(String[] args)throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        for(int end:List.of(16,17)){
            var p=YoonseoPlan.build(LocalDate.of(2026,11,end));check(p.equals(YoonseoPlan.build(LocalDate.of(2026,11,end))),"Deterministic plan");
            for(int month:List.of(7,8,9,10,11)){
                long amount=p.payments().stream().filter(x->x.card().equals("NORI")&&x.day().getMonthValue()==month).mapToLong(YoonseoPlan.Payment::amount).sum();
                check(amount==(month==11?15000L*end:month==8||month==9?490000:400000),"Monthly performance total");
            }
            long balance=p.opening();for(var r:p.bankRows()){balance+=r.movement().signedAmount();check(balance==r.balance()&&balance>=0,"Running balance");}
            check(balance==800000,"Closing balance");
            long gross=p.payments().stream().filter(x->x.card().equals("ZERO")&&x.day().getMonthValue()==10).mapToLong(x->x.amount()-x.benefit()).sum();check(gross==100000,"Net card bill");
            check(p.payments().stream().filter(x->x.card().equals("NORI")&&x.day().getMonthValue()==11).mapToLong(YoonseoPlan.Payment::benefit).sum()==5000,"Nori remaining monthly allowance");
            check(p.payments().stream().allMatch(x->!x.day().isAfter(LocalDate.of(2026,11,end))),"No future purchases");
        }
        reject(()->YoonseoPlan.build(LocalDate.of(2026,11,18)));
        // LOCA 대형 구매가 서버 반복 지출 규칙에 걸리지 않는다. 예전처럼 모두 쿠팡이면 걸린다(규칙이 실제로 작동하는지 확인).
        var split=new HashMap<String,Map<YearMonth,Integer>>();var same=new HashMap<String,Map<YearMonth,Integer>>();
        for(int m=8;m<=11;m++){
            split.computeIfAbsent(YoonseoRewards.BRAND.get(m)+":"+YoonseoRewards.gross(m),k->new HashMap<>()).merge(YearMonth.of(2026,m),1,Integer::sum);
            same.computeIfAbsent("COUPANG:"+YoonseoRewards.gross(m),k->new HashMap<>()).merge(YearMonth.of(2026,m),1,Integer::sum);
        }
        check(!YoonseoRewards.recurring(split.values()),"Large purchases must not become a recurring candidate");
        check(YoonseoRewards.recurring(same.values()),"Same merchant three months is a recurring candidate");
        check(new HashSet<>(YoonseoRewards.BRAND.values()).size()==4,"Distinct merchant per month");
        System.out.println("PURE TESTS OK checks="+checks);
        if(!args[0].equals("integration"))return;
        String url=YoonseoSeed.env("YOONSEO_TEST_JDBC_URL");
        try(var c=DriverManager.getConnection(url,YoonseoSeed.env("YOONSEO_DB_USERNAME"),YoonseoSeed.env("YOONSEO_DB_PASSWORD"))){
            try(var st=c.createStatement();var rs=st.executeQuery("SELECT DATABASE()")){rs.next();check(rs.getString(1).startsWith("yoonseo_test_"),"Test DB guard");}
            var in=(ObjectNode)YoonseoSeed.read(Path.of(args[1]));
            in.put("ssafyMemberId","y987654321@yamu.app").put("ssafyUserKey","fictional-test-key").put("accountNo","0041234567890123")
                    .put("pin","194862").put("noriCardNo","9410111820264111").put("zeroCardNo","9410111820264222");
            var random=new java.security.SecureRandom();String[] k=new String[3];for(int i=0;i<3;i++){byte[] bytes=new byte[32];random.nextBytes(bytes);k[i]=Base64.getEncoder().encodeToString(bytes);}
            var keys=new CryptoKeys(new CryptoProperties(1,Map.of(1,k[0]),k[1],k[2]));
            var seed=new YoonseoSeed(c,in,keys,Base64.getDecoder().decode(k[1]));seed.schema();seed.catalog();seed.checkIdentity();
            long before=seed.db.queryForObject("SELECT COUNT(*) FROM app_user",Long.class);
            c.setAutoCommit(false);
            boolean partialFailed=false;
            try{seed.createIdentity();}catch(org.springframework.dao.DataIntegrityViolationException expected){partialFailed=true;}
            finally{c.rollback();c.setAutoCommit(true);}
            check(partialFailed,"Missing registry triggers FK failure after user insert");
            check(seed.db.queryForObject("SELECT COUNT(*) FROM app_user",Long.class)==before,"Identity rollback leaves no partial user");
            var proof=new YoonseoSeed.BankProof(LocalDate.of(2026,11,17),List.of(new YoonseoSeed.BankRow("TEST-FUNDING",LocalDate.of(2026,11,17),LocalTime.of(9,0),"DEPOSIT",800000,800000)));
            seed.apply(c,proof);
            long count=seed.db.queryForObject("SELECT COUNT(*) FROM payment_transaction",Long.class);seed.apply(c,proof);
            check(seed.db.queryForObject("SELECT COUNT(*) FROM payment_transaction",Long.class)==count,"Idempotent replay");
            var changed=in.deepCopy().put("pin","194863");var wrong=new YoonseoSeed(c,changed,keys,Base64.getDecoder().decode(k[1]));reject(wrong::existing);
            var other=in.deepCopy().put("datasetKey","yoonseo-other-dataset");var collision=new YoonseoSeed(c,other,keys,Base64.getDecoder().decode(k[1]));reject(collision::checkIdentity);
            var mapper=new ObjectMapper().registerModule(new JavaTimeModule());
            for(String snapshot:seed.db.queryForList("SELECT recommendation_result_json FROM payment_transaction WHERE user_id=? AND recommendation_result_json IS NOT NULL",String.class,seed.user)){
                var r=mapper.readValue(snapshot,RecommendationResult.class);check(r.topCandidate().orElseThrow().card().userCardId()==seed.nori,"Recommendation detail deserializes");
            }
            // Public report service is in the newer target server runtime; exercise it without starting Spring.
            var readerClass=Class.forName("com.yamu.be.report.ReportReader");
            var readerCtor=readerClass.getDeclaredConstructors()[0];readerCtor.setAccessible(true);
            var reader=readerCtor.newInstance(new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(seed.db));
            var serviceClass=Class.forName("com.yamu.be.report.ReportService");var ctor=serviceClass.getDeclaredConstructors()[0];ctor.setAccessible(true);
            Object[] deps=Arrays.stream(ctor.getParameterTypes()).map(t->t==Clock.class?Clock.fixed(Instant.parse("2026-11-18T03:00:00Z"),ZoneOffset.UTC):reader).toArray();
            var reports=ctor.newInstance(deps);var method=serviceClass.getMethod("benefits",long.class,YearMonth.class);
            var report=mapper.valueToTree(method.invoke(reports,seed.user,YearMonth.of(2026,11)));
            Path output=Path.of("build/yoonseo-seed/benefit-report-november.json");Files.createDirectories(output.getParent());Files.writeString(output,mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));
            System.out.println("REPORT SERVICE OK: "+report.path("summary"));
            check(report.path("summary").path("expectedBenefit").asLong()==30500,"Actual report total");
            check(report.path("summary").path("confirmedBenefit").asLong()==25200,"Confirmed historical discount");
            check(report.path("summary").path("yamuPickExtra").asLong()==4800,"Actual recommended extra benefit");
            check(report.path("cards").size()==3,"Three cards in report");
            var october=mapper.valueToTree(method.invoke(reports,seed.user,YearMonth.of(2026,10)));
            check(october.path("summary").path("expectedBenefit").asLong()==36806,"October report total");
            check(october.path("summary").path("confirmedBenefit").asLong()==24806,"Actual confirmed billing discount");
            Files.writeString(output.resolveSibling("benefit-report-october.json"),mapper.writerWithDefaultPrettyPrinter().writeValueAsString(october));
            try(var competing=DriverManager.getConnection(url,YoonseoSeed.env("YOONSEO_DB_USERNAME"),YoonseoSeed.env("YOONSEO_DB_PASSWORD"))){
                String lock="yoonseo-seed:"+in.path("datasetKey").asText();
                check(seed.db.queryForObject("SELECT GET_LOCK(?,0)",Integer.class,lock)==1,"First owner obtains seed lock");
                try(var s=competing.prepareStatement("SELECT GET_LOCK(?,0)")){s.setString(1,lock);try(var r=s.executeQuery()){r.next();check(r.getInt(1)==0,"Concurrent seed cannot own same lock");}}
                seed.db.queryForObject("SELECT RELEASE_LOCK(?)",Integer.class,lock);
            }
            System.out.println("INTEGRATION TESTS OK checks="+checks+" user="+seed.user+" payments="+count);
        }
    }
}
