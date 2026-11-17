import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.security.SecureRandom;

/** Explicit financial sandbox preparation, separate from the DB loader. Persists intent before each write. */
public final class YoonseoBankPrepare {
    static final SecureRandom RANDOM=new SecureRandom();
    static ObjectNode state;
    static Path journal;
    static void save()throws Exception {privateWrite(journal,state);}
    static void privateWrite(Path path,JsonNode value)throws Exception {
        Path temp=path.resolveSibling(path.getFileName()+".tmp");
        try {Files.createFile(temp,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));}
        catch(UnsupportedOperationException e){Files.createFile(temp);}
        Files.writeString(temp,YoonseoSeed.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value));
        try{Files.move(temp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
        catch(AtomicMoveNotSupportedException e){Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING);}
    }
    public static void main(String[] args){
        try {
            YoonseoSeed.require(args.length==2&&args[0].equals("prepare"),"Use prepare and a new *.private.json input path");
            Path input=Path.of(args[1]).toAbsolutePath();
            YoonseoSeed.require(input.getFileName().toString().endsWith(".private.json"),"Use a private JSON file");
            try(var channel=java.nio.channels.FileChannel.open(input.resolveSibling(input.getFileName()+".lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
                    var lock=channel.tryLock()) {
            YoonseoSeed.require(lock!=null,"Another bank preparation is running for this input");
            YoonseoSeed.require("true".equals(YoonseoSeed.env("YOONSEO_PREPARE_BANK")),"Explicit sandbox preparation switch required");
            ObjectNode spec=(ObjectNode)YoonseoSeed.read(input);
            var through=LocalDate.parse(spec.path("historyThrough").asText());
            YoonseoSeed.require(through.plusDays(1).equals(LocalDate.now(YoonseoSeed.KST)),"Prepare the day after historyThrough");
            journal=input.resolveSibling(input.getFileName()+".bank.private.json");
            if(Files.exists(journal))state=(ObjectNode)YoonseoSeed.JSON.readTree(Files.readString(journal));
            else {
                YoonseoSeed.require(spec.path("ssafyUserKey").asText().startsWith("FILL"),"Existing bindings must not be replaced");
                state=YoonseoSeed.JSON.createObjectNode().put("datasetKey",spec.path("datasetKey").asText())
                        .put("member","y"+String.format(Locale.ROOT,"%09d",RANDOM.nextInt(1000000000))+"@yamu.app");save();
            }
            YoonseoSeed.require(state.path("datasetKey").asText().equals(spec.path("datasetKey").asText()),"Journal belongs to a different dataset");
            var bank=new YoonseoSeed.ReadOnlyBank("");
            if(!state.has("userKey")){
                JsonNode member;
                if(state.path("memberAttempted").asBoolean())member=bank.member(state.path("member").asText());
                else {state.put("memberAttempted",true);save();member=bank.send("/member",YoonseoSeed.JSON.valueToTree(Map.of("apiKey",YoonseoSeed.env("SSAFY_API_KEY"),"userId",state.path("member").asText())));}
                YoonseoSeed.require(member.hasNonNull("userKey"),"Member result unresolved; reconcile journal before another create");
                state.put("userKey",member.path("userKey").asText());save();
            }
            bank=new YoonseoSeed.ReadOnlyBank(state.path("userKey").asText());
            if(!state.has("accountNo")){
                var accounts=bank.call("inquireDemandDepositAccountList",Map.of());
                YoonseoSeed.require(accounts.isArray()&&accounts.size()<=1,"Unexpected account list; reconcile journal");
                if(accounts.size()==1){YoonseoSeed.require(state.path("accountAttempted").asBoolean()&&accounts.get(0).path("bankCode").asText().equals("004"),"Unexpected account; do not adopt unrelated bank account");state.put("accountNo",accounts.get(0).path("accountNo").asText());save();}
                else {
                    YoonseoSeed.require(!state.path("accountAttempted").asBoolean(),"Account creation result unresolved; no blind retry");
                    var products=operation(bank,"inquireDemandDepositList",Map.of(),false);String product=null;
                    for(var p:products)if(p.path("bankCode").asText().equals("004")){product=p.path("accountTypeUniqueNo").asText();break;}
                    YoonseoSeed.require(product!=null&&!product.isBlank(),"KB demand deposit product must exist");
                    state.put("accountAttempted",true);save();
                    var account=operation(bank,"createDemandDepositAccount",Map.of("accountTypeUniqueNo",product),true);
                    YoonseoSeed.require(account.hasNonNull("accountNo"),"Account creation result unresolved");state.put("accountNo",account.path("accountNo").asText());save();
                }
            }
            String account=state.path("accountNo").asText();
            long balance=bank.call("inquireDemandDepositAccountBalance",Map.of("accountNo",account)).path("accountBalance").asLong(-1);
            if(balance==0){
                YoonseoSeed.require(!state.path("fundingAttempted").asBoolean(),"Funding result unresolved; no blind retry");
                state.put("fundingAttempted",true);save();
                operation(bank,"updateDemandDepositAccountDeposit",Map.of("accountNo",account,"transactionBalance","800000","transactionSummary","YAMU YOONSEO DEMO"),true);
            }
            spec.put("ssafyMemberId",state.path("member").asText()).put("ssafyUserKey",state.path("userKey").asText()).put("accountNo",account);
            if(spec.path("pin").asText().startsWith("FILL"))spec.put("pin",String.format(Locale.ROOT,"%06d",RANDOM.nextInt(1000000)));
            for(String card:List.of("noriCardNo","zeroCardNo"))if(spec.path(card).asText().startsWith("FILL"))spec.put(card,"9410"+String.format(Locale.ROOT,"%012d",RANDOM.nextLong(1000000000000L)));
            // Verify ownership + complete one-row funding evidence before publishing ready bindings.
            YoonseoSeed.bankProof(spec,through);YoonseoSeed.validate(spec,true);
            privateWrite(input,spec);state.put("ready",true);save();
            System.out.println("BANK READY: dedicated KB sandbox account, balance 800000. Private bindings saved; no credentials printed.");
            }
        }catch(Exception e){
            System.err.println("BANK PREPARE INCOMPLETE: "+e.getClass().getSimpleName()+
                    (e instanceof YoonseoPlan.SeedFailure?" / "+e.getMessage():" / inspect private journal; do not blindly retry an unresolved write"));System.exit(1);
        }
    }
    static JsonNode operation(YoonseoSeed.ReadOnlyBank bank,String name,Map<String,String> body,boolean user)throws Exception{
        YoonseoSeed.require(Set.of("inquireDemandDepositList","createDemandDepositAccount","updateDemandDepositAccountDeposit").contains(name),"Unsupported preparation operation");
        String tx=LocalDateTime.now(YoonseoSeed.KST).format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))+String.format(Locale.ROOT,"%06d",RANDOM.nextInt(1000000));
        if(!name.startsWith("inquire")){state.put(name+"TransactionNo",tx);save();}
        ObjectNode data=YoonseoSeed.JSON.valueToTree(body);var h=data.putObject("Header");
        h.put("apiName",name).put("transmissionDate",tx.substring(0,8)).put("transmissionTime",tx.substring(8,14)).put("institutionCode","00100").put("fintechAppNo","001").put("apiServiceCode",name).put("institutionTransactionUniqueNo",tx).put("apiKey",YoonseoSeed.env("SSAFY_API_KEY"));
        if(user)h.put("userKey",state.path("userKey").asText());
        var response=bank.send("/edu/demandDeposit/"+name,data);
        YoonseoSeed.require(response.path("Header").path("responseCode").asText().equals("H0000")&&response.hasNonNull("REC"),"Bank preparation not confirmed; reconcile journal");return response.get("REC");
    }
}
