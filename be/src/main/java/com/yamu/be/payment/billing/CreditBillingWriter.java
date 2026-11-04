package com.yamu.be.payment.billing;

import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.payment.domain.PaymentTransaction;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 신용카드 승인의 청구 회차 저장(명세 「결제 승인 요청」 처리 순서 5). 회차가 걸리는 달마다 청구서(card_bill)를 확보하고,
 * 승인이 저장된 트랜잭션 안에서 회차(bill_item)를 붙인다.
 *
 * <p>청구서 확보는 승인 트랜잭션 밖의 짧은 트랜잭션(REQUIRES_NEW)이다. 같은 카드·같은 달 청구서는 UNIQUE라 두 결제가 동시에
 * 만들면 한쪽이 충돌하는데, 그때는 다시 읽어 같은 청구서를 쓴다. 승인 저장이 실패해 빈 ACCUMULATING 청구서만 남아도 다음 승인이 재사용한다.
 */
@Slf4j
@Component
public class CreditBillingWriter {

    /** 같은 카드·같은 청구 월 청구서는 하나다. 이 이름의 충돌만 동시 생성으로 보고 다시 읽는다. */
    static final String CARD_MONTH_KEY = "uk_bill_card_month";
    /** 회차를 붙일 청구서가 이미 닫혔을 때 422의 사유(임시: 명세에 없는 값) */
    static final String BILL_CLOSED = "BILL_CLOSED";

    private final CardBillRepository bills;
    private final BillItemRepository items;
    private final TransactionTemplate newTransaction;

    public CreditBillingWriter(CardBillRepository bills, BillItemRepository items,
            PlatformTransactionManager transactionManager) {
        this.bills = bills;
        this.items = items;
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** 계획의 회차가 들어갈 달마다 청구서를 찾거나 만든다. 승인 트랜잭션 전에 부른다. */
    public Map<YearMonth, CardBill> ensureBills(Long userId, Long userCardId, InstallmentPlan plan) {
        Map<YearMonth, CardBill> result = new LinkedHashMap<>();
        for (InstallmentPlan.Installment installment : plan.installments()) {
            result.computeIfAbsent(installment.billingMonth(), month -> ensureBill(userId, userCardId, installment));
        }
        return result;
    }

    private CardBill ensureBill(Long userId, Long userCardId, InstallmentPlan.Installment installment) {
        return bills.findByUserCardIdAndBillingMonth(userCardId, installment.billingMonth().atDay(1))
                .map(CreditBillingWriter::requireAccumulating)
                .orElseGet(() -> create(userId, userCardId, installment));
    }

    /**
     * 회차는 이용기간이 진행 중인 청구서에만 붙인다. 확정·출금·미납 청구서에 붙으면 확정된 청구 금액이 바뀐다.
     * 청구 월 계산이 맞으면 일어나지 않는다(결제일 변경 미반영은 S15P31E103-205). 같은 카드로는 다시 해도 계속 실패하므로
     * 500이 아니라 422 {@code reason=BILL_CLOSED}로 알려 다른 카드를 고르게 한다. 승인 저장 전이라 승인 서비스가 결제를
     * 바로 REQUESTED로 되돌린다.
     */
    private static CardBill requireAccumulating(CardBill bill) {
        if (bill.getStatus() != BillStatus.ACCUMULATING) {
            log.error("회차를 붙일 청구서가 이용기간 진행 중이 아님, 운영 확인 cardBillId={} userCardId={} month={} status={}",
                    bill.getId(), bill.getUserCardId(), bill.getBillingMonth(), bill.getStatus());
            throw new BusinessException(ErrorCode.DATA_INSUFFICIENT, "이 카드의 이번 청구서가 이미 마감돼 지금은 결제할 수 없습니다.",
                    Map.of("reason", BILL_CLOSED));
        }
        return bill;
    }

    private CardBill create(Long userId, Long userCardId, InstallmentPlan.Installment installment) {
        try {
            return newTransaction.execute(status -> bills.saveAndFlush(
                    CardBill.accumulating(userId, userCardId, installment.cycle(), installment.withdrawalDate())));
        } catch (DataIntegrityViolationException e) {
            if (!isSameMonthDuplicate(e)) {
                // FK·CHECK 위반 등은 동시 생성이 아니다
                throw e;
            }
            // 같은 달 청구서를 다른 결제가 방금 만들었다
            log.info("청구서 동시 생성 충돌, 다시 읽음 userCardId={} month={}", userCardId, installment.billingMonth());
            return bills.findByUserCardIdAndBillingMonth(userCardId, installment.billingMonth().atDay(1))
                    .map(CreditBillingWriter::requireAccumulating)
                    .orElseThrow(() -> new IllegalStateException("청구서를 만들지도 찾지도 못했습니다", e));
        }
    }

    private static boolean isSameMonthDuplicate(DataIntegrityViolationException e) {
        String message = e.getMostSpecificCause().getMessage();
        return message != null && message.contains(CARD_MONTH_KEY);
    }

    /**
     * 승인된 결제의 회차를 적는다. 호출하는 쪽의 트랜잭션(승인 저장) 안에서 부른다.
     * 회차의 사용자·카드는 결제 행의 값이어야 FK({@code fk_item_payment_owner})가 맞는다.
     */
    public List<BillItem> writeItems(PaymentTransaction payment, InstallmentPlan plan, Map<YearMonth, CardBill> bills) {
        List<BillItem> created = new ArrayList<>(plan.installments().size());
        for (InstallmentPlan.Installment installment : plan.installments()) {
            CardBill bill = bills.get(installment.billingMonth());
            if (bill == null || !bill.getUserCardId().equals(payment.getUserCardId())) {
                throw new IllegalStateException("회차의 청구서가 없거나 카드가 다릅니다 (결제 " + payment.getId() + ")");
            }
            created.add(BillItem.pending(payment.getId(), payment.getUserId(), payment.getUserCardId(), bill.getId(),
                    installment.no(), installment.billingMonth().atDay(1), installment.principal()));
        }
        List<BillItem> saved = items.saveAll(created);
        items.flush();
        return saved;
    }
}
