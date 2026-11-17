import java.time.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import com.yamu.be.global.crypto.CipherField;

/** Settled large-purchase history: genuine catalog rate, explicitly synthetic bank evidence. */
public final class YoonseoRewards {
 public static void create(YoonseoSeed s)throws Exception {
  long product=s.product("LOCA LIKIT 2.0");
  long benefit=s.benefit(product,"DOMESTIC 가맹점 할인","DISCOUNT","AT_BILLING",0.012);
  YoonseoSeed.require(s.db.queryForObject("SELECT COUNT(*) FROM benefit_limit_link WHERE card_benefit_id=?",Integer.class,benefit)==0,"LOCA policy unexpectedly capped");
  YoonseoSeed.require(s.db.queryForObject("SELECT min_performance_amount FROM card_benefit WHERE card_benefit_id=?",Long.class,benefit)==0,"LOCA performance policy changed");
  String number="9420"+s.text("zeroCardNo").substring(4);String account=s.text("accountNo");
  var prior=s.db.queryForList("SELECT user_card_id FROM user_card WHERE user_id=? AND card_no_hash=?",Long.class,s.user,s.lookup.card(number));
  if(!prior.isEmpty()) {verify(s,prior.get(0));return;}
  long card=s.insert("user_card",s.timed("user_id",s.user,"card_product_id",product,"linked_account_id",s.account,"issuer_account_bank_code","004","issuer_account_hash",s.lookup.account("004",account),"issuer_account_last4",account.substring(account.length()-4),"card_no_enc",s.cipher.encrypt(number,CipherField.USER_CARD_NO),"card_no_hash",s.lookup.card(number),"card_last4",number.substring(12),"card_alias","LOCA · 큰 지출 혜택","payment_day",16,"status","INACTIVE","issuer_status","NORMAL","registered_at",YoonseoSeed.at(LocalDate.of(2026,7,31),9),"issuer_registered_on",LocalDate.of(2025,1,1)));
  var merchant=s.merchant("COUPANG");
  for(int month=8;month<=11;month++) {
   var date=LocalDate.of(2026,month,2);var paid=LocalDate.of(2026,month,16);long gross=month==11?2100000:2000000;long discount=gross*12/1000;long net=gross-discount;
   String key=UUID.nameUUIDFromBytes((s.text("datasetKey")+":settled-large:"+month).getBytes(StandardCharsets.UTF_8)).toString();
   var snap=YoonseoSeed.JSON.createObjectNode().put("source","YOONSEO_REWARDS_V1").put("synthetic",true).put("brandCode","COUPANG").put("purpose","planned large household purchase");
   long payment=s.insert("payment_transaction",s.timed("user_id",s.user,"user_card_id",card,"card_product_id",product,"merchant_id",merchant.get("merchant_id"),"payment_channel","ONLINE","facility_type","GENERAL","merchant_context_snapshot",snap.toString(),"request_key",key,"request_fingerprint",MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)),"source","YAMU","status","APPROVED","requested_amount",gross,"approved_amount",gross,"installment_months",1,"recognized_performance_amount",gross,"performance_basis_amount",0,"performance_month",date.withDayOfMonth(1),"requested_at",YoonseoSeed.at(date,12),"approved_at",YoonseoSeed.at(date,12),"spending_class","LARGE","spending_class_source","USER","memo","시연용 가전·가구 대형 구매 · 납부 완료"));
   long applied=s.insert("payment_benefit",YoonseoSeed.row("payment_transaction_id",payment,"card_product_id",product,"card_benefit_id",benefit,"benefit_value",discount,"value_unit","KRW","krw_amount",discount,"created_at",YoonseoSeed.at(date,12)));
   long bill=s.insert("card_bill",s.timed("user_id",s.user,"user_card_id",card,"linked_account_id",s.account,"billing_month",date.withDayOfMonth(1),"period_start",date.minusMonths(1).withDayOfMonth(17),"period_end",date.withDayOfMonth(9),"withdrawal_date",paid,"status","PAID","confirmed_amount",net,"paid_amount",net,"confirmed_at",YoonseoSeed.at(date.withDayOfMonth(10),9),"paid_at",YoonseoSeed.at(paid,9)));
   long item=s.insert("bill_item",s.timed("payment_transaction_id",payment,"user_id",s.user,"user_card_id",card,"card_bill_id",bill,"installment_no",1,"billing_month",date.withDayOfMonth(1),"principal_amount",gross,"fee_amount",0,"billed_amount",gross,"status","PAID"));
   s.insert("bill_item_discount",YoonseoSeed.row("card_bill_id",bill,"bill_item_id",item,"payment_benefit_id",applied,"amount",discount,"created_at",YoonseoSeed.at(date.withDayOfMonth(10),9)));
   s.insert("card_monthly_performance",s.timed("user_card_id",card,"performance_month",date.withDayOfMonth(1),"recognized_amount",gross));
  }
  verify(s,card);
 }
 static void verify(YoonseoSeed s,long card) {
  YoonseoSeed.require(s.db.queryForObject("SELECT COUNT(*) FROM card_bill WHERE user_id=? AND user_card_id=? AND status='PAID' AND confirmed_amount=paid_amount",Integer.class,s.user,card)==4,"Settled rewards bills mismatch");
  YoonseoSeed.require(s.db.queryForObject("SELECT SUM(d.amount) FROM bill_item_discount d JOIN card_bill b USING(card_bill_id) WHERE b.user_id=? AND b.user_card_id=? AND b.billing_month='2026-11-01'",Long.class,s.user,card)==25200,"Confirmed rewards mismatch");
  YoonseoSeed.require(s.db.queryForObject("SELECT status FROM user_card WHERE user_card_id=?",String.class,card).equals("INACTIVE"),"History card must remain outside recommendations");
 }
}
