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

    /** 원 청구서의 출금 성공을 기다리는 환급인지. 금액은 예상값이고 입금하면 안 된다. */
    public boolean isAwaitingWithdrawal() {
        return status == RefundStatus.PENDING && dueDate == null;
    }

    /**
     * 출금 성공을 확인한 뒤 금액과 기한을 확정한다. 금액은 그 회차에 실제로 출금된 몫이다.
     *
     * @param dueDate 출금 성공일 + 3영업일
     */
    public void confirmAfterWithdrawal(long amount, LocalDate dueDate) {
        if (!isAwaitingWithdrawal()) {
            throw new IllegalStateException("출금을 기다리는 환급이 아닙니다 (환급 " + id + ", 상태 " + status + ", 기한 " + this.dueDate + ")");
        }
        if (amount <= 0) {
            throw new IllegalArgumentException("확정 환급액은 양수여야 합니다 (환급 " + id + ")");
        }
        this.principalAmount = amount;
        this.refundAmount = amount;
        this.dueDate = dueDate;
    }

    /** 입금할 환급인지(Task 5.4.4). 기한이 정해져 금액이 확정된 대기 환급만 입금한다. */
    public boolean isDepositTarget() {
        return status == RefundStatus.PENDING && dueDate != null;
    }

    /**
     * 입금 요청을 보내기 직전에 요청 번호를 남긴다. 결과를 모르면 이 번호의 시각으로 유예를 따지고, 다시 보낼 때는 새 번호로 바꾼다.
     * 금융망을 부르기 전에 커밋해야 한다.
     */
    public void recordSending(String finRequestNo) {
        requireDepositTarget("입금 요청을 남길");
        if (finRequestNo == null || !finRequestNo.matches("\\d{20}")) {
            throw new IllegalArgumentException("기관거래고유번호는 20자리 숫자여야 합니다 (환급 " + id + ")");
        }
        this.finRequestNo = finRequestNo;
    }

    /** 요청이 금융망에 가지 않았다(돈이 움직이지 않음). 남긴 번호가 그 요청의 것일 때만 지워 다음 실행이 유예 없이 다시 보내게 한다. */
    public void clearSending(String sentRequestNo) {
        if (isDepositTarget() && sentRequestNo.equals(this.finRequestNo)) {
            this.finRequestNo = null;
        }
    }

    /**
     * 입금을 확인했다(입금 성공 응답이나 거래내역의 태그).
     *
     * @param bankTransactionNo SSAFY 입금 거래 번호
     * @param completedAt       확인한 시각(UTC)
     */
    public void complete(String bankTransactionNo, LocalDateTime completedAt) {
        requireDepositTarget("완료할");
        if (linkedAccountId == null) {
            throw new IllegalStateException("환급 계좌가 없는 환급은 완료할 수 없습니다 (환급 " + id + ")");
        }
        this.status = RefundStatus.COMPLETED;
        this.finTransactionNo = bankTransactionNo;
        this.completedAt = completedAt;
    }

    /** 은행이 입금을 거절했다(계좌 없음 등). 자동으로 다시 보내지 않고 운영이 본다. */
    public void fail() {
        requireDepositTarget("실패로 바꿀");
        this.status = RefundStatus.FAILED;
    }

    private void requireDepositTarget(String action) {
        if (!isDepositTarget()) {
            throw new IllegalStateException("입금할 환급이 아니라 " + action + " 수 없습니다 (환급 " + id + ", 상태 " + status
                    + ", 기한 " + dueDate + ")");
        }
    }

    /** 원 청구서가 출금 실패(미납)로 끝나 돌려줄 돈이 없다. 출금을 기다리던 환급만 무효로 바꾼다. */
    public void voidForUnpaid() {
        if (!isAwaitingWithdrawal()) {
            throw new IllegalStateException("출금을 기다리는 환급만 무효로 바꿀 수 있습니다 (환급 " + id + ", 상태 " + status + ")");
        }
        this.status = RefundStatus.CANCELED;
    }
}
