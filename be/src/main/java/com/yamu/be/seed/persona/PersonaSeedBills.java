package com.yamu.be.seed.persona;

import com.yamu.be.global.calendar.BusinessDayCalendar;
import com.yamu.be.payment.billing.BillItem;
import com.yamu.be.payment.billing.BillItemRepository;
import com.yamu.be.payment.billing.BillingCycle;
import com.yamu.be.payment.billing.CardBill;
import com.yamu.be.payment.billing.CardBillRepository;
import com.yamu.be.payment.billing.InstallmentPlan;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 시연 페르소나의 등록 전 신용카드 사용분(SEED 결제)으로 아직 출금되지 않았을 청구서를 만든다(S15P31E103-371, 2026-11-09 결정).
 * 이게 없으면 "지난달 실적"은 보이는데 잔고예보·청구서 화면에 이번 결제일 카드값 출금이 없다(운영 앱 점검 B-06, S15P31E103-357).
 *
 * <ul>
 *   <li>규칙은 신용 승인과 같다. 청구 주기는 {@link BillingCycle}(카드 결제일·상품 마감 일수), 할부 나누기와 출금 예정일은
 *       {@link InstallmentPlan}(영업일 보정)이다. 청구할인은 없다(SEED 결제에는 혜택 행이 없다).</li>
 *   <li>출금 예정일이 시드를 넣는 날(오늘) 이후인 청구 월의 회차만 만든다. 그 전 회차는 YAMU를 쓰기 전에 이미 낸 것으로 본다.
 *       출금 완료 이력은 출금 계좌가 있어야 만들 수 있어({@code ck_bill_paid}) 넣지 않는다.</li>
 *   <li>청구서는 모두 진행 중(ACCUMULATING), 회차는 청구 전(PENDING)이다. 시드 때 카드에는 출금 계좌가 없어 확정할 수 없다
 *       ({@code ck_bill_confirmed}). 청구 확정 작업은 계좌 없는 청구서를 건너뛰다가, 가입·카드 등록으로 계좌가 붙으면 이용기간이
 *       끝난 청구서를 확정하고 결제일에 출금한다. 잔고예보는 진행 중 청구서를 카드의 지금 계좌로 읽어 등록 직후부터 보인다.</li>
 *   <li>SEED 결제는 취소할 수 없어(PaymentCancelService) 이 회차가 환급으로 바뀌지 않는다.</li>
 * </ul>
 */
@Component
class PersonaSeedBills {
    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");

    private final JdbcTemplate jdbc;
    private final CardBillRepository bills;
    private final BillItemRepository items;
    private final BusinessDayCalendar calendar;

    PersonaSeedBills(JdbcTemplate jdbc, CardBillRepository bills, BillItemRepository items,
            BusinessDayCalendar calendar) {
        this.jdbc = jdbc;
        this.bills = bills;
        this.items = items;
        this.calendar = calendar;
    }

    /**
     * 이 사용자의 신용카드 SEED 결제로 청구서와 회차를 만든다. 부르는 쪽(페르소나 시드)의 트랜잭션 안에서, SEED 결제를 넣은 뒤 부른다.
     *
     * @param today 한국 날짜 오늘. 출금 예정일이 이 날 이후인 회차만 만든다
     * @return 만든 청구서 수
     */
    int insert(long userId, LocalDate today) {
        List<SeedPayment> payments = jdbc.query("""
                SELECT p.payment_transaction_id, p.user_card_id, p.approved_amount, p.installment_months, p.approved_at,
                       c.payment_day, cp.billing_cutoff_days
                  FROM payment_transaction p
                  JOIN user_card c ON c.user_card_id = p.user_card_id AND c.user_id = p.user_id
                  JOIN card_product cp ON cp.card_product_id = c.card_product_id
                 WHERE p.user_id = ? AND p.source = 'SEED' AND p.status = 'APPROVED' AND cp.card_type = 'CREDIT'
                 ORDER BY p.payment_transaction_id
                """, (rs, n) -> new SeedPayment(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getInt(4),
                rs.getTimestamp(5), (Integer) rs.getObject(6), (Integer) rs.getObject(7)), userId);

        Map<BillKey, CardBill> billByMonth = new LinkedHashMap<>();
        List<Pending> pending = new ArrayList<>();
        for (SeedPayment payment : payments) {
            if (payment.paymentDay() == null || !BillingCycle.supports(payment.paymentDay(), payment.cutoffDays())) {
                // 시드 검증이 신용카드 결제일을 요구하므로 생기지 않는다. 생겨도 승인처럼 청구 주기를 만들 수 없어 넣지 않는다
                continue;
            }
            BillingCycle cycle = BillingCycle.of(payment.paymentDay(), payment.cutoffDays());
            LocalDate usageDate = payment.approvedAt().toInstant().atZone(KOREA).toLocalDate();
            InstallmentPlan plan = InstallmentPlan.of(payment.amount(), payment.months(), cycle, usageDate, calendar);
            for (InstallmentPlan.Installment installment : plan.installments()) {
                if (installment.withdrawalDate().isBefore(today)) {
                    continue;
                }
                BillKey key = new BillKey(payment.userCardId(), installment.billingMonth());
                billByMonth.computeIfAbsent(key, k -> CardBill.accumulating(userId, payment.userCardId(),
                        installment.cycle(), installment.withdrawalDate()));
                pending.add(new Pending(payment, installment, key));
            }
        }
        if (billByMonth.isEmpty()) {
            return 0;
        }
        bills.saveAll(billByMonth.values());
        bills.flush();
        items.saveAll(pending.stream().map(p -> BillItem.pending(p.payment().id(), userId, p.payment().userCardId(),
                billByMonth.get(p.key()).getId(), p.installment().no(), p.installment().billingMonth().atDay(1),
                p.installment().principal())).toList());
        items.flush();
        return billByMonth.size();
    }

    private record SeedPayment(long id, long userCardId, long amount, int months, Timestamp approvedAt,
            Integer paymentDay, Integer cutoffDays) {}

    private record BillKey(long userCardId, YearMonth month) {}

    private record Pending(SeedPayment payment, InstallmentPlan.Installment installment, BillKey key) {}
}
