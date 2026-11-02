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
import java.time.LocalDate;
import java.time.LocalDateTime;
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
}
