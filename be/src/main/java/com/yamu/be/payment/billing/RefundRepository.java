package com.yamu.be.payment.billing;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefundRepository extends JpaRepository<Refund, Long> {

    List<Refund> findByPaymentTransactionIdOrderByBillItemIdAsc(Long paymentTransactionId);

    /** 회차들의 환급. 회차당 한 행이다({@code uk_refund_item}). */
    List<Refund> findByBillItemIdIn(Collection<Long> billItemIds);

    /**
     * 입금할 환급(대기 + 기한 있음)을 번호 순으로 50건씩. {@code ix_refund_due (status, due_date)}를 탄다.
     * 기한 없는 대기 환급은 원 청구서 출금을 기다리는 중이라 빠진다.
     */
    List<Refund> findTop50ByStatusAndDueDateIsNotNullAndIdGreaterThanOrderByIdAsc(RefundStatus status, Long id);

    /** 입금 요청 기록·결과 저장 전에 같은 환급을 다루는 다른 실행과 줄을 세운다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Refund r WHERE r.id = :id")
    Optional<Refund> findByIdForUpdate(@Param("id") Long id);
}
