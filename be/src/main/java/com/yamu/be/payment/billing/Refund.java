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
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * refund. 취소된 신용카드 결제의 청구 회차 하나에 대한 환급(POL-006). 일시불도 1회차로 기록한다.
 * 회차당 한 행이고({@code uk_refund_item}), 회차·원거래·사용자는 FK로 맞물린다. 입금은 환급 배치(Task 5.4.4)가 한다.
 *
 * <p>체크카드 취소는 이 표를 쓰지 않는다(「결제 상태·예외 처리」 4-4).
 */
@Entity
@Table(name = "refund")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Refund extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "refund_id")
    private Long id;

    @Column(name = "payment_transaction_id", nullable = false)
    private Long paymentTransactionId;

    @Column(name = "bill_item_id", nullable = false)
    private Long billItemId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 환급 계좌. 입금 전에 확정한다 */
    @Column(name = "linked_account_id")
    private Long linkedAccountId;

    /** 서버가 만든 환급 작업 UUID. 재시도해도 바꾸지 않는다 */
    @Column(name = "request_key", nullable = false, columnDefinition = "char(36)")
    private String requestKey;

    @Column(name = "principal_amount", nullable = false)
    private long principalAmount;

    @Column(name = "fee_amount", nullable = false)
    private long feeAmount;

    /** 원금 + 수수료({@code ck_refund_amounts}). 0원은 저장할 수 없다 */
    @Column(name = "refund_amount", nullable = false)
    private long refundAmount;

    /** 한국 업무 날짜 환급 기한. 원 청구서 출금 전이면 출금 성공 뒤에 정한다 */
    @Column(name = "due_date")
    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private RefundStatus status;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "fin_request_no", columnDefinition = "char(20)")
    private String finRequestNo;

    @Column(name = "fin_transaction_no", length = 128)
    private String finTransactionNo;

    @Version
    @Column(nullable = false)
    private Long version;

    /**
     * 취소 때 만드는 대기 환급. YAMU 모의 무이자(POL-013)라 수수료는 0원이다.
     *
     * @param dueDate 출금이 끝난 회차는 기한, 출금 전 회차는 null
     */
    public static Refund pending(Long paymentTransactionId, Long billItemId, Long userId, Long linkedAccountId,
            long principalAmount, LocalDate dueDate) {
        if (principalAmount <= 0) {
            throw new IllegalArgumentException("환급 원금은 양수여야 합니다 (회차 " + billItemId + ")");
        }
        Refund refund = new Refund();
        refund.paymentTransactionId = paymentTransactionId;
        refund.billItemId = billItemId;
        refund.userId = userId;
        refund.linkedAccountId = linkedAccountId;
        refund.requestKey = UUID.randomUUID().toString();
        refund.principalAmount = principalAmount;
        refund.feeAmount = 0;
        refund.refundAmount = principalAmount;
        refund.dueDate = dueDate;
        refund.status = RefundStatus.PENDING;
        return refund;
    }
}
