package com.yamu.be.payment.billing;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefundRepository extends JpaRepository<Refund, Long> {

    List<Refund> findByPaymentTransactionIdOrderByBillItemIdAsc(Long paymentTransactionId);

    /** 회차들의 환급. 회차당 한 행이다({@code uk_refund_item}). */
    List<Refund> findByBillItemIdIn(Collection<Long> billItemIds);

    /**
     * 입금할 환급(대기 + 기한 있음)을 번호 순으로 한 페이지씩. {@code ix_refund_due (status, due_date)}를 탄다.
     * 기한 없는 대기 환급은 원 청구서 출금을 기다리는 중이라 빠진다.
     *
     * @param userId  한 사용자만(수동 실행의 시연 계정). null이면 전체
     * @param afterId 이 번호 뒤부터
     */
    @Query("""
            SELECT r FROM Refund r
            WHERE r.status = :status AND r.dueDate IS NOT NULL AND r.id > :afterId
              AND (:userId IS NULL OR r.userId = :userId)
            ORDER BY r.id ASC""")
    List<Refund> findPayoutTargets(@Param("status") RefundStatus status, @Param("userId") Long userId,
            @Param("afterId") Long afterId, Pageable page);

    /** 입금 요청 기록·결과 저장 전에 같은 환급을 다루는 다른 실행과 줄을 세운다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Refund r WHERE r.id = :id")
    Optional<Refund> findByIdForUpdate(@Param("id") Long id);
}
