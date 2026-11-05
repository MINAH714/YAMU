package com.yamu.be.payment.billing;

import com.yamu.be.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * card_bill. 신용카드 한 장의 한 달 청구서. 그달 첫 신용 승인 때 ACCUMULATING으로 생기고(Task 5.2.4),
 * 이용기간 마감 다음 날 확정(Task 5.4.2), 결제일에 출금(Task 5.4.3)된다. 같은 카드·같은 청구 월은 하나뿐이다({@code uk_bill_card_month}).
 *
 * <p>사용자·카드·계좌는 연관관계 대신 번호로 둔다. 출금 계좌({@code linkedAccountId})는 청구 확정 때 그 시점의 계좌로 채운다.
 */
@Entity
@Table(name = "card_bill")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CardBill extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "card_bill_id")
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "user_card_id", nullable = false)
    private Long userCardId;

    /** 청구 확정 당시 출금 계좌. 확정 전에는 null */
    @Column(name = "linked_account_id")
    private Long linkedAccountId;

    /** 한국 기준 청구 월의 1일 */
    @Column(name = "billing_month", nullable = false)
    private LocalDate billingMonth;

    /** 이용기간 시작(포함) */
    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    /** 이용기간 마감(포함) = 결제일 − N일 */
    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    /** 출금 예정일(한국 날짜). 결제일이 비영업일이면 다음 영업일 */
    @Column(name = "withdrawal_date", nullable = false)
    private LocalDate withdrawalDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private BillStatus status;

    /** 확정 원본 청구액. 확정 후 덮어쓰지 않는다 */
    @Column(name = "confirmed_amount")
    private Long confirmedAmount;

    @Column(name = "paid_amount", nullable = false)
    private long paidAmount;

    /** 취소로 납부 대상에서 제외한 미납 원금 */
    @Column(name = "canceled_unpaid_principal", nullable = false)
    private long canceledUnpaidPrincipal;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    @Column(name = "failed_at")
    private LocalDateTime failedAt;

    @Column(name = "fin_request_no", columnDefinition = "char(20)")
    private String finRequestNo;

    @Column(name = "fin_transaction_no", length = 128)
    private String finTransactionNo;

    @Version
    @Column(nullable = false)
    private Long version;

    /** 그달 첫 신용 승인 때 만드는 빈 청구서. 회차가 붙을 때마다 합계는 조회로 계산한다(확정액은 확정 때 고정). */
    public static CardBill accumulating(Long userId, Long userCardId, BillingCycle.Cycle cycle, LocalDate withdrawalDate) {
        CardBill bill = new CardBill();
        bill.userId = userId;
        bill.userCardId = userCardId;
        bill.billingMonth = cycle.billingMonth().atDay(1);
        bill.periodStart = cycle.periodStart();
        bill.periodEnd = cycle.periodEnd();
        bill.withdrawalDate = withdrawalDate;
        bill.status = BillStatus.ACCUMULATING;
        bill.paidAmount = 0;
        bill.canceledUnpaidPrincipal = 0;
        return bill;
    }

    /**
     * 청구 확정(Task 5.4.2, POL-005). 이용기간이 끝난 청구서의 청구 전 회차 합계를 확정 원본으로 고정하고, 그 시점의 출금 계좌와
     * 영업일로 다시 계산한 출금 예정일을 적는다. 확정 뒤에는 금액을 덮어쓰지 않는다(확정 뒤 취소는 환급으로 처리).
     *
     * @param confirmedAmount 확정 청구액(원, 0 이상). 회차가 모두 제외됐으면 0
     * @param linkedAccountId 확정 당시 카드의 출금 계좌. 없으면 확정할 수 없다({@code ck_bill_confirmed})
     * @param withdrawalDate  결제일을 영업일로 보정한 출금 예정일(한국 날짜)
     * @param confirmedAt     확정 시각(UTC)
     */
    public void confirm(long confirmedAmount, Long linkedAccountId, LocalDate withdrawalDate, LocalDateTime confirmedAt) {
        if (status != BillStatus.ACCUMULATING) {
            throw new IllegalStateException("이용기간 진행 중인 청구서만 확정할 수 있습니다 (청구서 " + id + ", 상태 " + status + ")");
        }
        if (confirmedAmount < 0) {
            throw new IllegalArgumentException("확정 청구액은 0원 이상이어야 합니다 (청구서 " + id + ", 금액 " + confirmedAmount + ")");
        }
        if (linkedAccountId == null || withdrawalDate == null || confirmedAt == null) {
            throw new IllegalArgumentException("확정에는 출금 계좌·출금 예정일·확정 시각이 필요합니다 (청구서 " + id + ")");
        }
        this.confirmedAmount = confirmedAmount;
        this.linkedAccountId = linkedAccountId;
        this.withdrawalDate = withdrawalDate;
        this.confirmedAt = confirmedAt;
        this.status = BillStatus.CONFIRMED;
    }

    /**
     * 확정액이 0원인 청구서(회차가 모두 확정 전에 취소됐거나, 승인 저장 실패로 회차 없이 남은 청구서)는 출금할 돈이 없다.
     * 확정 직후 출금 없이 PAID(출금액 0)로 닫아, 카드 해제·탈퇴가 빈 청구서 때문에 막히지 않게 한다. 임시(2026-11-05 결정):
     * 명세에는 없는 처리라 PAID 대신 다른 상태를 둘지는 명세에 반영할 때 정한다.
     */
    public void settleWithoutWithdrawal(LocalDateTime paidAt) {
        if (status != BillStatus.CONFIRMED || confirmedAmount == null || confirmedAmount != 0 || paidAmount != 0) {
            throw new IllegalStateException("확정액 0원의 확정 청구서만 출금 없이 닫을 수 있습니다 (청구서 " + id + ", 상태 " + status
                    + ", 확정액 " + confirmedAmount + ")");
        }
        this.paidAt = paidAt;
        this.status = BillStatus.PAID;
    }

    /**
     * 미납액 중 취소된 회차 몫을 납부 대상에서 뺀다(POL-006). 확정 원본({@code confirmedAmount})·실패 이력은 그대로 두고
     * 제외액만 쌓는다. 몫은 확정액 안에서의 그 회차 금액이라(청구할인이 생기면 할인 뒤 금액) 할인을 두 번 빼지 않는다.
     * 남은 미납액보다 많이 뺄 수는 없다.
     *
     * <p>빼고 나서 남은 미납액이 정확히 0원이면 상태를 CANCELED로 바꾼다. 금액과 상태를 같은 곳에서 같이 바꿔, 상태로 미납을 보는 곳
     * (추천·카드 관리·승인)과 금액이 어긋나지 않게 한다. 0원보다 크면 UNPAID 그대로다.
     */
    public void waiveUnpaidPrincipal(long share) {
        if (status != BillStatus.UNPAID) {
            throw new IllegalStateException("미납 청구서만 미납액을 뺄 수 있습니다 (청구서 " + id + ", 상태 " + status + ")");
        }
        if (share <= 0 || share > outstandingUnpaid()) {
            throw new IllegalStateException("뺄 금액이 남은 미납액을 넘습니다 (청구서 " + id + ", 금액 " + share
                    + ", 남은 미납액 " + outstandingUnpaid() + ")");
        }
        this.canceledUnpaidPrincipal += share;
        if (outstandingUnpaid() == 0) {
            this.status = BillStatus.CANCELED;
        }
    }

    /** 확정액 전부가 출금됐는지. 청구액 일부 출금은 지원하지 않으므로 출금 완료 청구서는 늘 그래야 한다. */
    public boolean isFullyPaid() {
        return status == BillStatus.PAID && confirmedAmount != null && paidAmount == confirmedAmount;
    }

    /** 남은 미납액 = 확정액 − 출금액 − 취소로 뺀 미납 원금. 확정 전이면 0. */
    public long outstandingUnpaid() {
        return confirmedAmount == null ? 0 : confirmedAmount - paidAmount - canceledUnpaidPrincipal;
    }
}
