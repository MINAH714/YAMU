package com.yamu.be.cashflow;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CashflowOccurrenceRepository extends JpaRepository<CashflowOccurrence, Long> {
    /** 원래 회차나 옮긴 예정일이 기간 안인 사용자의 회차. 기간 밖으로 옮긴 회차를 빼고, 안으로 옮긴 회차를 넣는 데 쓴다. */
    @Query("""
            select o from CashflowOccurrence o
            where o.userId = :userId
              and (o.occurrenceDate between :from and :to or o.scheduledDate between :from and :to)
            """)
    List<CashflowOccurrence> findForUserBetween(@Param("userId") Long userId,
            @Param("from") LocalDate from, @Param("to") LocalDate to);

    /** 실제 결제와 연결된 회차 ID. 연결 테이블은 결제 쪽 기능이 쓰므로 엔티티 없이 읽기만 한다. ids는 비어 있으면 안 된다. */
    @Query(value = "select cashflow_occurrence_id from cashflow_payment_match where cashflow_occurrence_id in (:ids)",
            nativeQuery = true)
    List<Long> findMatchedIds(@Param("ids") Collection<Long> ids);

    Optional<CashflowOccurrence> findByCashflowItemIdAndOccurrenceDate(Long cashflowItemId, LocalDate occurrenceDate);
}
