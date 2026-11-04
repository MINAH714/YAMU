package com.yamu.be.payment.domain;

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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * payment_transaction. 모의 결제 1건. POS 요청으로 REQUESTED로 생기고, 승인·거절·취소·만료가 같은 행을 고친다.
 *
 * <p>사용자·카드·가맹점은 연관관계 대신 번호로 둔다. 결제 조건(금액·개월 수·채널·시설·POS 조건 근거)은 POS 최초 요청 값을
 * 보존하고 재시도·승인·취소가 그대로 쓴다. 승인 이후 칸(승인액·실적·출금 번호 등)은 Task 5.2.x에서 채운다.
 */
@Entity
@Table(name = "payment_transaction")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentTransaction extends BaseTimeEntity {

    /** 출금 결과를 모른 채 거래내역에도 없어 거절한 사유(approve.FailureCode와 같은 값). 늦은 출금 확인의 대상이다 */
    public static final String WITHDRAWAL_NOT_CONFIRMED = "WITHDRAWAL_NOT_CONFIRMED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "payment_transaction_id")
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 승인된 카드. 카드 선택 전(REQUESTED)에는 null이고 cardProductId와 함께 채운다 */
    @Column(name = "user_card_id")
    private Long userCardId;

    @Column(name = "card_product_id")
    private Long cardProductId;

    /** 브랜드 단위 가맹점 */
    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_channel", nullable = false, length = 16)
    private PaymentChannel paymentChannel;

    @Enumerated(EnumType.STRING)
    @Column(name = "facility_type", length = 32)
    private FacilityType facilityType;

    /** 서버가 확인한 POS·브랜드·업종·거래 조건의 당시 근거(JSON). 사용자 입력으로 바꾸지 않는다 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "merchant_context_snapshot")
    private String merchantContextSnapshot;

    /** POS의 Idempotency-Key(UUID). UNIQUE */
    @Column(name = "request_key", nullable = false, columnDefinition = "char(36)")
    private String requestKey;

    /** 최초 요청의 불변 내용(POS·가맹점·바코드·금액·결제 방식·개월 수·주문번호)의 SHA-256. 같은 키 재요청 비교용 */
    @Column(name = "request_fingerprint", nullable = false, columnDefinition = "binary(32)")
    private byte[] requestFingerprint;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TransactionSource source;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private PaymentStatus status;

    @Column(name = "requested_amount", nullable = false)
    private Long requestedAmount;

    @Column(name = "approved_amount")
    private Long approvedAmount;

    /** 일시불 1, 할부는 총 개월 수. POS 조건이라 요청 때 정해진다 */
    @Column(name = "installment_months", columnDefinition = "smallint")
    private Integer installmentMonths;

    @Column(name = "recognized_performance_amount")
    private Long recognizedPerformanceAmount;

    @Column(name = "performance_basis_amount")
    private Long performanceBasisAmount;

    @Column(name = "performance_month")
    private LocalDate performanceMonth;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "canceled_at")
    private LocalDateTime canceledAt;

    @Column(name = "performance_reversed_at")
    private LocalDateTime performanceReversedAt;

    @Column(name = "failure_code", length = 64)
    private String failureCode;

    @Column(name = "fin_request_no", columnDefinition = "char(20)")
    private String finRequestNo;

    @Column(name = "fin_transaction_no", length = 128)
    private String finTransactionNo;

    /** 소비 분류(BE4 영역). 결제는 읽지 않는다 */
    @Column(name = "spending_class", length = 16)
    private String spendingClass;

    @Column(name = "spending_class_source", length = 16)
    private String spendingClassSource;

    @Column(length = 100)
    private String memo;

    @Version
    @Column(nullable = false)
    private Long version;

    /** POS 결제 요청 접수. 카드는 아직 없고 상태는 REQUESTED다. */
    public static PaymentTransaction request(Long userId, Long merchantId, PaymentChannel channel, FacilityType facility,
            String merchantContextSnapshot, String requestKey, byte[] requestFingerprint, long requestedAmount,
            int installmentMonths, LocalDateTime requestedAt) {
        PaymentTransaction payment = new PaymentTransaction();
        payment.userId = userId;
        payment.merchantId = merchantId;
        payment.paymentChannel = channel;
        payment.facilityType = facility;
        payment.merchantContextSnapshot = merchantContextSnapshot;
        payment.requestKey = requestKey;
        payment.requestFingerprint = requestFingerprint;
        payment.source = TransactionSource.YAMU;
        payment.status = PaymentStatus.REQUESTED;
        payment.requestedAmount = requestedAmount;
        payment.installmentMonths = installmentMonths;
        payment.requestedAt = requestedAt;
        return payment;
    }

    public boolean isInstallment() {
        return installmentMonths != null && installmentMonths > 1;
    }

    /** 결제 확정 마감(DEC-003). POS 요청 시각에서 확정 시간(60초)이 지나면 승인할 수 없다. */
    public LocalDateTime confirmDeadline(Duration window) {
        return requestedAt.plus(window);
    }

    /** 마감 시각 정각부터 만료로 본다. */
    public boolean isPastDeadline(Duration window, Instant now) {
        return !now.isBefore(confirmDeadline(window).toInstant(ZoneOffset.UTC));
    }

    /** 앱 승인 요청을 받을 수 있는 상태인지. REQUESTED이거나, 거절 뒤 다른 카드로 재승인하는 DECLINED다. */
    public boolean isConfirmable() {
        return status == PaymentStatus.REQUESTED || status == PaymentStatus.DECLINED;
    }

    /**
     * 앱 승인 요청이 이 결제를 선점했다(명세 「결제 상태·예외 처리」 4-2). REQUESTED·DECLINED(재승인)에서만 PROCESSING이 된다.
     * 같은 행을 두 요청이 동시에 바꾸면 {@code version}이 어긋나 한 건만 저장된다. 재승인은 앞 시도의 카드·거절 사유를 덮어쓴다.
     */
    public void startApproval(Long userCardId, Long cardProductId) {
        requireStatus("승인 시작", PaymentStatus.REQUESTED, PaymentStatus.DECLINED);
        this.userCardId = userCardId;
        this.cardProductId = cardProductId;
        this.failureCode = null;
        // 앞 시도의 금융망 요청·거래 번호는 지운다. 남겨 두면 복구가 옛 시도의 출금을 이번 시도로 오인한다(옛 번호는 Redis 시도 기록에 있다)
        this.finRequestNo = null;
        this.finTransactionNo = null;
        this.status = PaymentStatus.PROCESSING;
    }

    /**
     * 금융망 출금을 부르기 전에 기관거래고유번호를 남긴다(명세 1장 멱등성: PROCESSING과 요청 번호를 먼저 저장).
     * 결과를 모를 때 이 번호의 날짜·태그로 거래내역을 찾는다. 한 시도에 한 번만 받는다.
     */
    public void assignFinRequestNo(String finRequestNo) {
        requireStatus("출금 요청 번호 저장", PaymentStatus.PROCESSING);
        if (this.finRequestNo != null) {
            throw new IllegalStateException("이미 출금 요청 번호가 있습니다 (결제 " + id + ")");
        }
        this.finRequestNo = finRequestNo;
    }

    /**
     * 승인 실행이 금융망에 아무것도 보내지 못했다(채번 실패·연결 실패). 돈이 움직이지 않았으므로 결제를 승인 전(REQUESTED)으로 되돌려
     * 다시 승인할 수 있게 한다. 임시(명세 4-2에 없는 역방향 전이): 카드는 남기고 요청 번호·거절 사유는 지운다.
     */
    public void releaseApproval() {
        requireStatus("승인 되돌리기", PaymentStatus.PROCESSING);
        this.finRequestNo = null;
        this.failureCode = null;
        this.status = PaymentStatus.REQUESTED;
    }

    /**
     * 모의 승인 확정. DB CHECK {@code ck_payment_approval}이 승인 시각과 함께 카드·승인액·실적 인정액·실적 월·개월 수를 요구한다.
     *
     * @param performanceMonth  실적을 합산할 월(한국 날짜, 1일로 저장)
     * @param finTransactionNo  체크카드 출금의 금융망 거래 번호. 신용카드·0원 승인은 null
     */
    public void approve(long approvedAmount, long recognizedPerformanceAmount, LocalDate performanceMonth,
            LocalDateTime approvedAt, String finTransactionNo) {
        requireStatus("승인", PaymentStatus.PROCESSING);
        this.approvedAmount = approvedAmount;
        this.recognizedPerformanceAmount = recognizedPerformanceAmount;
        this.performanceMonth = performanceMonth;
        this.approvedAt = approvedAt;
        this.finTransactionNo = finTransactionNo;
        this.failureCode = null;
        this.status = PaymentStatus.APPROVED;
    }

    /**
     * 거래내역에 없어 "출금 미확인"으로 거절·만료한 결제인데, 같은 요청 번호의 출금이 뒤늦게 확인됐는지 볼 수 있는 상태인지.
     * 다음 재승인이 시작되면(startApproval) 요청 번호가 지워지므로 그 뒤에는 거짓이다.
     */
    public boolean isLateConfirmable(String finRequestNo) {
        return (status == PaymentStatus.DECLINED || status == PaymentStatus.EXPIRED)
                && WITHDRAWAL_NOT_CONFIRMED.equals(failureCode)
                && finRequestNo != null && finRequestNo.equals(this.finRequestNo);
    }

    /**
     * 출금 미확인으로 거절·만료한 뒤 같은 요청 번호의 출금이 확인됐다(은행의 늦은 처리). 돈이 나갔으므로 승인으로 바로잡는다.
     * 임시(명세 4-2에 없는 DECLINED·EXPIRED → APPROVED 전이, Task 5.2.3 리뷰): 요청 번호가 같을 때만 허용한다.
     */
    public void confirmLateWithdrawal(String finRequestNo, long approvedAmount, long recognizedPerformanceAmount,
            LocalDate performanceMonth, LocalDateTime approvedAt, String finTransactionNo) {
        if (!isLateConfirmable(finRequestNo)) {
            throw new IllegalStateException("늦은 출금 확인으로 승인할 수 없는 결제입니다 (결제 " + id + ", 상태 " + status + ")");
        }
        this.approvedAmount = approvedAmount;
        this.recognizedPerformanceAmount = recognizedPerformanceAmount;
        this.performanceMonth = performanceMonth;
        this.approvedAt = approvedAt;
        this.finTransactionNo = finTransactionNo;
        this.failureCode = null;
        this.status = PaymentStatus.APPROVED;
    }

    /** 확정 거절. 사유는 명세 failure.code와 같은 값이다. 어떤 카드로 거절됐는지 보이도록 카드는 남긴다. */
    public void decline(String failureCode) {
        requireStatus("거절", PaymentStatus.PROCESSING);
        this.failureCode = failureCode;
        this.status = PaymentStatus.DECLINED;
    }

    /** 확정 시간 안에 승인되지 않음. 출금이 걸려 있을 수 있는 PROCESSING은 만료시키지 않는다(복구는 Task 5.2.3). */
    public void expire() {
        requireStatus("만료", PaymentStatus.REQUESTED, PaymentStatus.DECLINED);
        this.status = PaymentStatus.EXPIRED;
    }

    /**
     * 전체 취소(POL-006, Task 5.3.2). APPROVED에서만 CANCELED가 된다. 승인 당시 값(승인액·실적 인정액·실적 월·출금 번호)은
     * 지우지 않는다. 실적·한도 복구는 원장 포트가 같은 트랜잭션에서 한다.
     */
    public void cancel(LocalDateTime canceledAt) {
        requireStatus("취소", PaymentStatus.APPROVED);
        this.canceledAt = canceledAt;
        this.status = PaymentStatus.CANCELED;
    }

    private void requireStatus(String action, PaymentStatus... allowed) {
        for (PaymentStatus candidate : allowed) {
            if (status == candidate) {
                return;
            }
        }
        throw new IllegalStateException(action + "할 수 없는 상태입니다: " + status + " (결제 " + id + ")");
    }
}
