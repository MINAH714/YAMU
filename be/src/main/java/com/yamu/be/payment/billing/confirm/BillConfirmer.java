package com.yamu.be.payment.billing.confirm;

import com.yamu.be.global.calendar.BusinessDayCalendar;
import com.yamu.be.payment.billing.BillItem;
import com.yamu.be.payment.billing.BillItemRepository;
import com.yamu.be.payment.billing.BillItemStatus;
import com.yamu.be.payment.billing.BillStatus;
import com.yamu.be.payment.billing.BillingCycle;
import com.yamu.be.payment.billing.CardBill;
import com.yamu.be.payment.billing.CardBillRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 청구서 한 건의 청구 확정(Task 5.4.2, POL-005, API 명세 4장 「청구 확정」). 한 트랜잭션에서:
 *
 * <pre>
 * ① 청구서 잠금(SELECT ... FOR UPDATE). 이미 확정됐으면 아무것도 하지 않음(재실행·동시 실행)
 * ② 청구 전(PENDING) 회차 합계 − 청구할인 = 확정액. 확정 전 취소로 빠진 회차(EXCLUDED)는 넣지 않음.
 *   청구할인은 회차별 배분을 {@code bill_item_discount}에 함께 저장한다(Task 5.4.7)
 * ③ 출금 계좌 = 지금 카드의 연결 계좌(연결 상태). 없으면 확정하지 않고 다음 실행에 다시
 * ④ 출금 예정일 = (이용기간 마감 + 마감 일수 = 원래 결제일)을 영업일로 다시 계산. 승인 때 저장한 값은 그 해 공휴일 발표 전 예정일일 수 있다
 * ⑤ 청구서 CONFIRMED·회차 BILLED. 확정액 0원이면 출금 없이 PAID. 커밋 뒤 알림용 이벤트
 * </pre>
 *
 * <p>잠금 순서는 청구서 → 회차로 취소·승인 회차 저장과 같다. 취소가 먼저 잠그면 확정은 취소가 끝난 상태(EXCLUDED)를 읽고,
 * 확정이 먼저면 취소는 BILLED 회차를 보고 환급 대기로 처리한다. 승인은 회차를 적기 전에 청구서를 잠가 상태를 다시 보므로
 * 확정된 청구서에 회차가 붙지 않는다({@code CreditBillingWriter#writeItems}).
 */
@Slf4j
@Component
public class BillConfirmer {

    private final CardBillRepository bills;
    private final BillItemRepository items;
    private final BillingDiscountSource discounts;
    private final BusinessDayCalendar calendar;
    private final NamedParameterJdbcTemplate jdbc;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transaction;

    public BillConfirmer(CardBillRepository bills, BillItemRepository items, BillingDiscountSource discounts,
            BusinessDayCalendar calendar, NamedParameterJdbcTemplate jdbc, ApplicationEventPublisher events,
            TransactionTemplate transaction) {
        this.bills = bills;
        this.items = items;
        this.discounts = discounts;
        this.calendar = calendar;
        this.jdbc = jdbc;
        this.events = events;
        this.transaction = transaction;
    }

    /**
     * 청구서 하나를 확정한다. 계좌 없음·이미 처리됨은 결과로, 데이터 이상(할인이 회차 합보다 큼, 청구액 미산정 등)은 예외로 알린다.
     * 예외면 트랜잭션이 롤백되어 청구서는 ACCUMULATING 그대로다.
     *
     * @param now 확정 시각(UTC). 기준일과 상관없이 실제 시각
     */
    public Result confirm(Long cardBillId, LocalDateTime now) {
        return transaction.execute(status -> confirmLocked(cardBillId, now));
    }

    private Result confirmLocked(Long cardBillId, LocalDateTime now) {
        CardBill bill = bills.findAllForUpdate(List.of(cardBillId)).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("청구서가 없습니다 (청구서 " + cardBillId + ")"));
        if (bill.getStatus() != BillStatus.ACCUMULATING) {
            return Result.alreadyDone(bill);
        }
        Optional<CardTerms> terms = cardTerms(bill);
        if (terms.isEmpty()) {
            log.warn("청구 확정 보류: 카드의 출금 계좌가 없거나 끊김, 다음 실행에 다시 cardBillId={} userCardId={}", bill.getId(),
                    bill.getUserCardId());
            return Result.noAccount(bill);
        }
        List<BillItem> pending = items.findByCardBillIdIn(List.of(cardBillId)).stream()
                .filter(item -> item.getStatus() == BillItemStatus.PENDING)
                .toList();
        // 청구할인을 회차에 나눠 저장한다(Task 5.4.7). 취소 환급·미납 제외 몫(BillShares)이 이 배분으로 정해진다.
        // 회차를 BILLED로 바꾸기 전에 물어본다(포트는 청구 전 회차를 DB에서 읽는다)
        List<BillingDiscountSource.Allocation> allocations = discounts.allocate(cardBillId);
        long itemAmount = 0;
        for (BillItem item : pending) {
            item.bill();
            itemAmount = Math.addExact(itemAmount, item.getBilledAmount());
        }
        long discount = BillingDiscountSource.Allocation.total(allocations);
        if (discount > itemAmount) {
            throw new IllegalStateException("청구할인이 회차 청구액 합계보다 큽니다 (청구서 " + cardBillId + ", 할인 " + discount
                    + ", 회차 합 " + itemAmount + ")");
        }
        saveDiscounts(cardBillId, pending, allocations, now);
        long confirmedAmount = itemAmount - discount;
        LocalDate paymentDate = bill.getPeriodEnd().plusDays(terms.get().cutoffDays());
        LocalDate withdrawalDate = calendar.businessDayOnOrAfter(paymentDate);
        if (!withdrawalDate.equals(bill.getWithdrawalDate())) {
            log.info("청구 확정에서 출금 예정일을 다시 계산함 cardBillId={} before={} after={}", bill.getId(),
                    bill.getWithdrawalDate(), withdrawalDate);
        }
        bill.confirm(confirmedAmount, terms.get().linkedAccountId(), withdrawalDate, now);
        boolean settled = confirmedAmount == 0;
        if (settled) {
            bill.settleWithoutWithdrawal(now);
        }
        items.flush();
        bills.flush();
        events.publishEvent(new BillConfirmedEvent(bill.getId(), bill.getUserId(), bill.getUserCardId(),
                confirmedAmount, withdrawalDate, settled));
        log.info("청구 확정 cardBillId={} items={} discount={} amount={} withdrawalDate={} status={}", bill.getId(),
                pending.size(), discount, confirmedAmount, withdrawalDate, bill.getStatus());
        return Result.confirmed(bill, pending.size());
    }

    /**
     * 회차별 청구할인 배분을 남긴다. 청구서를 잠근 트랜잭션 안이라 같은 청구서를 두 번 확정하지 않고,
     * {@code uk_item_discount}가 같은 회차·혜택의 중복을 막는다. 이 청구서의 청구 전 회차가 아닌 배분이 오거나, 한 회차의 배분 합이
     * 그 회차 청구액을 넘으면 저장하지 않고 실패시킨다(확정 전체가 롤백되어 다음 실행·운영이 다시 본다). 회차 몫이 청구액을 넘은 채
     * 저장되면 그 회차를 취소할 때 {@code BillShares}의 몫이 음수가 되어 취소가 계속 실패하기 때문이다(!141 리뷰). 원천도 청구액까지만
     * 넣지만 원천 구현에 기대지 않고 여기서 한 번 더 본다.
     */
    private void saveDiscounts(Long cardBillId, List<BillItem> pending, List<BillingDiscountSource.Allocation> allocations,
            LocalDateTime now) {
        Map<Long, Long> billedById = pending.stream()
                .collect(Collectors.toMap(BillItem::getId, BillItem::getBilledAmount));
        Map<Long, Long> allocatedById = new HashMap<>();
        for (BillingDiscountSource.Allocation allocation : allocations) {
            Long billed = billedById.get(allocation.billItemId());
            if (billed == null || allocation.amount() <= 0) {
                throw new IllegalStateException("이 청구서의 청구 전 회차가 아닌 할인 배분입니다 (청구서 " + cardBillId + ", 회차 "
                        + allocation.billItemId() + ", 금액 " + allocation.amount() + ")");
            }
            long allocated = allocatedById.merge(allocation.billItemId(), allocation.amount(), Math::addExact);
            if (allocated > billed) {
                throw new IllegalStateException("회차에 나눈 청구할인이 그 회차 청구액보다 큽니다 (청구서 " + cardBillId + ", 회차 "
                        + allocation.billItemId() + ", 할인 " + allocated + ", 청구액 " + billed + ")");
            }
        }
        for (BillingDiscountSource.Allocation allocation : allocations) {
            jdbc.update("""
                    INSERT INTO bill_item_discount (card_bill_id, bill_item_id, payment_benefit_id, amount, created_at)
                    VALUES (:bill, :item, :benefit, :amount, :now)
                    """, new MapSqlParameterSource("bill", cardBillId).addValue("item", allocation.billItemId())
                    .addValue("benefit", allocation.paymentBenefitId()).addValue("amount", allocation.amount())
                    .addValue("now", now));
        }
    }

    /**
     * 확정에 쓸 카드 조건: 지금 연결된 출금 계좌와 상품의 이용기간 마감 일수. 카드·계좌 표는 BE1 소유라 읽기만 한다.
     * 마감 일수가 없으면 승인과 같은 기본값(14일). 계좌가 없거나 끊겼으면 empty.
     */
    private Optional<CardTerms> cardTerms(CardBill bill) {
        return jdbc.query("""
                SELECT c.linked_account_id, p.billing_cutoff_days
                  FROM user_card c
                  JOIN card_product p ON p.card_product_id = c.card_product_id
                  JOIN linked_account a ON a.linked_account_id = c.linked_account_id AND a.user_id = c.user_id
                 WHERE c.user_card_id = :cardId AND c.user_id = :userId AND a.status = 'CONNECTED'
                """, new MapSqlParameterSource("cardId", bill.getUserCardId()).addValue("userId", bill.getUserId()),
                (rs, row) -> new CardTerms(rs.getLong(1), BillingCycle.of(1, (Integer) rs.getObject(2)).cutoffDays()))
                .stream().findFirst();
    }

    private record CardTerms(Long linkedAccountId, int cutoffDays) {
    }

    /** 확정 한 건의 결과. */
    public record Result(Kind kind, Long cardBillId, Long userId, BillStatus status, Long confirmedAmount,
            int billedItems) {

        static Result confirmed(CardBill bill, int billedItems) {
            return new Result(Kind.CONFIRMED, bill.getId(), bill.getUserId(), bill.getStatus(),
                    bill.getConfirmedAmount(), billedItems);
        }

        static Result alreadyDone(CardBill bill) {
            return new Result(Kind.ALREADY_DONE, bill.getId(), bill.getUserId(), bill.getStatus(),
                    bill.getConfirmedAmount(), 0);
        }

        static Result noAccount(CardBill bill) {
            return new Result(Kind.NO_ACCOUNT, bill.getId(), bill.getUserId(), bill.getStatus(), null, 0);
        }
    }

    public enum Kind {
        /** 이번에 확정함(0원이면 바로 PAID) */
        CONFIRMED,
        /** 다른 실행이 먼저 확정함 */
        ALREADY_DONE,
        /** 출금 계좌가 없어 확정하지 않음 */
        NO_ACCOUNT
    }
}
