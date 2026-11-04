package com.yamu.be.payment.billing;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BillItemRepository extends JpaRepository<BillItem, Long> {

    List<BillItem> findByPaymentTransactionIdOrderByInstallmentNoAsc(Long paymentTransactionId);

    /** 청구서들에 배정된 모든 회차(다른 결제 포함). 확정액을 회차별 몫으로 나눌 때 청구서를 잠근 뒤 읽는다. */
    List<BillItem> findByCardBillIdIn(Collection<Long> cardBillIds);

    /** 결제의 회차가 배정된 청구서 번호. 취소가 청구서를 먼저 잠그려고 엔티티를 읽기 전에 번호만 본다. */
    @Query("SELECT DISTINCT b.cardBillId FROM BillItem b WHERE b.paymentTransactionId = :paymentId AND b.cardBillId IS NOT NULL")
    List<Long> findCardBillIdsByPaymentTransactionId(@Param("paymentId") Long paymentTransactionId);

    /** 취소용 회차 잠금(SELECT ... FOR UPDATE). 회차 순. 청구서를 잠근 뒤에 부른다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM BillItem b WHERE b.paymentTransactionId = :paymentId ORDER BY b.installmentNo")
    List<BillItem> findForCancel(@Param("paymentId") Long paymentTransactionId);
}
