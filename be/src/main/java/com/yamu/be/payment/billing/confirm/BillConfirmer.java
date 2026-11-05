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
import java.util.List;
import java.util.Optional;
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
 * ② 청구 전(PENDING) 회차 합계 − 청구할인 = 확정액. 확정 전 취소로 빠진 회차(EXCLUDED)는 넣지 않음
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
     * 청구서 하나를 확정한다. 계좌 없음·이미 처리됨은 결과로, 데이터 이상(청구할인 있음, 청구액 미산정 등)은 예외로 알린다.
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
        long itemAmount = 0;
        for (BillItem item : pending) {
            item.bill();
            itemAmount = Math.addExact(itemAmount, item.getBilledAmount());
        }
        long discount = discounts.discount(bill, pending);
        if (discount != 0) {
            // 임시(2026-11-05): 회차별 할인 배분을 저장할 곳이 없어 취소 환급 몫을 정할 수 없다. 확정하지 않고 운영 확인
            throw new IllegalStateException("청구할인이 있는 청구서는 아직 확정할 수 없습니다(회차별 배분 저장 필요) (청구서 "
                    + cardBillId + ", 할인 " + discount + ")");
        }
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
        log.info("청구 확정 cardBillId={} items={} amount={} withdrawalDate={} status={}", bill.getId(), pending.size(),
                confirmedAmount, withdrawalDate, bill.getStatus());
        return Result.confirmed(bill, pending.size());
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
