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
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * bill_item. 신용카드 결제 한 건의 청구 회차(일시불 1회, 할부는 개월 수만큼). 승인 때 PENDING으로 만들고 청구 확정 때 BILLED가 된다.
 * 거래·회차 번호는 유일하고({@code uk_bill_item_installment}), 카드·사용자는 원결제·청구서와 같아야 한다(FK).
 *
 * <p>YAMU 모의 무이자 할부(POL-013)라 수수료는 명시적으로 0원이고 청구액 = 원금이다. 수수료 NULL은 "미산정"이라 쓰지 않는다.
 */
@Entity
@Table(name = "bill_item")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BillItem extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "bill_item_id")
    private Long id;

    @Column(name = "payment_transaction_id", nullable = false)
    private Long paymentTransactionId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "user_card_id", nullable = false)
    private Long userCardId;

    /** 배정 청구서. 승인 때 바로 그달 청구서에 붙인다 */
    @Column(name = "card_bill_id")
    private Long cardBillId;

    /** 일시불 1, 할부 1부터 */
    @Column(name = "installment_no", nullable = false, columnDefinition = "smallint")
    private Integer installmentNo;

    /** 예정 청구 월의 1일 */
    @Column(name = "billing_month", nullable = false)
    private LocalDate billingMonth;

    @Column(name = "principal_amount", nullable = false)
    private long principalAmount;

    /** 회차 수수료. 무이자 확정 0 */
    @Column(name = "fee_amount")
    private Long feeAmount;

    /** 원금 + 확정 수수료 */
    @Column(name = "billed_amount")
    private Long billedAmount;

    @Column(name = "annual_fee_rate", precision = 9, scale = 6)
    private BigDecimal annualFeeRate;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "fee_calculation_snapshot")
    private String feeCalculationSnapshot;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private BillItemStatus status;

    @Column(name = "unpaid_principal_waived_at")
    private LocalDateTime unpaidPrincipalWaivedAt;

    @Version
    @Column(nullable = false)
    private Long version;

    /** 승인 때 만드는 회차. 무이자라 수수료 0원, 청구액 = 원금. */
    public static BillItem pending(Long paymentTransactionId, Long userId, Long userCardId, Long cardBillId,
            int installmentNo, LocalDate billingMonth, long principalAmount) {
        BillItem item = new BillItem();
        item.paymentTransactionId = paymentTransactionId;
        item.userId = userId;
        item.userCardId = userCardId;
        item.cardBillId = cardBillId;
        item.installmentNo = installmentNo;
        item.billingMonth = billingMonth;
        item.principalAmount = principalAmount;
        item.feeAmount = 0L;
        item.billedAmount = principalAmount;
        item.status = BillItemStatus.PENDING;
        return item;
    }

    /** 청구 확정(Task 5.4.2). 청구 전(PENDING) 회차를 확정액에 넣는다. 청구액이 정해지지 않은 회차는 합산할 수 없다. */
    public void bill() {
        if (status != BillItemStatus.PENDING) {
            throw new IllegalStateException("청구 전 회차만 확정할 수 있습니다 (회차 " + id + ", 상태 " + status + ")");
        }
        if (billedAmount == null) {
            throw new IllegalStateException("청구액이 정해지지 않은 회차는 확정할 수 없습니다 (회차 " + id + ")");
        }
        this.status = BillItemStatus.BILLED;
    }

    /** 청구 전 취소(POL-006). 청구 확정 전(PENDING) 회차만 청구에서 뺀다. 환급은 없다. */
    public void exclude() {
        if (status != BillItemStatus.PENDING) {
            throw new IllegalStateException("청구 전 회차만 제외할 수 있습니다 (회차 " + id + ", 상태 " + status + ")");
        }
        this.status = BillItemStatus.EXCLUDED;
    }

    /**
     * 청구 확정 뒤 취소(POL-006). 확정된 청구액은 그대로 출금하므로 출금 전(BILLED)·출금 완료(PAID) 회차 모두 환급 대기가 된다.
     * 환급 행은 취소 처리가 따로 만든다.
     */
    public void awaitRefund() {
        if (status != BillItemStatus.BILLED && status != BillItemStatus.PAID) {
            throw new IllegalStateException("청구 확정 뒤 회차만 환급 대기로 바꿀 수 있습니다 (회차 " + id + ", 상태 " + status + ")");
        }
        this.status = BillItemStatus.REFUND_PENDING;
    }

    /**
     * 미납 회차 취소(POL-006). 낸 돈이 없으므로 환급하지 않고 납부 대상에서 뺀다. 미납 청구서의 회차를 취소할 때(BILLED)와,
     * 출금 전에 취소한 회차(REFUND_PENDING)의 청구서가 출금 실패로 끝났을 때 쓴다. 제외액은 청구서에 따로 쌓는다
     * ({@link CardBill#waiveUnpaidPrincipal}).
     */
    public void waiveUnpaid(LocalDateTime waivedAt) {
        if (status != BillItemStatus.BILLED && status != BillItemStatus.REFUND_PENDING) {
            throw new IllegalStateException("미납 청구서의 청구 회차만 제외할 수 있습니다 (회차 " + id + ", 상태 " + status + ")");
        }
        this.status = BillItemStatus.EXCLUDED;
        this.unpaidPrincipalWaivedAt = waivedAt;
    }

    /**
     * 청구 확정 뒤 취소했지만 그 회차 몫이 0원이라 돌려줄 돈도 낼 돈도 없다. 0원 환급 행은 만들지 않고 회차만 닫는다.
     * 임시: 이 경우의 회차 상태는 명세에 없어 EXCLUDED로 둔다.
     */
    public void closeWithoutRefund() {
        if (status != BillItemStatus.BILLED && status != BillItemStatus.PAID) {
            throw new IllegalStateException("청구 확정 뒤 회차만 닫을 수 있습니다 (회차 " + id + ", 상태 " + status + ")");
        }
        this.status = BillItemStatus.EXCLUDED;
    }

    /** 청구서 확정액에 들어간 회차인지. 확정 전에 취소돼 빠진 회차(EXCLUDED, 미납 제외 시각 없음)만 아니다. */
    public boolean isInConfirmedAmount() {
        return !(status == BillItemStatus.EXCLUDED && unpaidPrincipalWaivedAt == null) && status != BillItemStatus.PENDING;
    }
}
