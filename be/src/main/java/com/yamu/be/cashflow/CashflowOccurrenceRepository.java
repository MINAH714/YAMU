package com.yamu.be.cashflow;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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

    /**
     * ids 중 승인된 결제와 연결된 회차. 잔고예보는 이 회차를 실제 결제로 보고 예정 차감에서 뺀다.
     * 매칭은 승인된 결제만 받고 승인 뒤에는 취소로만 바뀌므로, 연결된 결제가 취소되면 다시 예정으로 센다.
     * 수정 잠금은 연결 해제 전까지 {@link #findMatchedIds}로 유지한다.
     */
    @Query(value = """
            select m.cashflow_occurrence_id from cashflow_payment_match m
            join payment_transaction p on p.payment_transaction_id = m.payment_transaction_id
            where m.cashflow_occurrence_id in (:ids) and p.status = 'APPROVED'
            """, nativeQuery = true)
    List<Long> findPaidIds(@Param("ids") Collection<Long> ids);

    /** 규칙 하나의 저장된 회차 행 전부. */
    List<CashflowOccurrence> findByCashflowItemId(Long cashflowItemId);

    Optional<CashflowOccurrence> findByCashflowItemIdAndOccurrenceDate(Long cashflowItemId, LocalDate occurrenceDate);

    /** 원래 회차나 예정일이 from 이후인 회차 중 결제와 연결된 것의 수. 이 회차들은 이후 수정·전체 삭제로 바꾸지 않는다. */
    @Query(value = """
            select count(*) from cashflow_occurrence o
            join cashflow_payment_match m on m.cashflow_occurrence_id = o.cashflow_occurrence_id
            where o.cashflow_item_id in (:itemIds) and (o.occurrence_date >= :from or o.scheduled_date >= :from)
            """, nativeQuery = true)
    long countMatchedFrom(@Param("itemIds") Collection<Long> itemIds, @Param("from") LocalDate from);

    /** 규칙의 회차 행 중 원래 회차가 from 이후인 것을 지운다. 결제 연결 회차가 없는지 먼저 확인해야 한다(FK). */
    @Modifying
    @Query("delete from CashflowOccurrence o where o.cashflowItemId = :itemId and o.occurrenceDate >= :from")
    int deleteFrom(@Param("itemId") Long itemId, @Param("from") LocalDate from);

    /** 원래 회차는 지났지만 이번 회차만 today 이후로 옮겨 둔 회차. 전체 삭제 때 EXCLUDED로 바꾼다. */
    @Query("""
            select o from CashflowOccurrence o
            where o.cashflowItemId = :itemId and o.occurrenceDate < :today and o.scheduledDate >= :today
              and o.status = com.yamu.be.cashflow.CashflowOccurrence.Status.CONFIRMED
            """)
    List<CashflowOccurrence> findMovedFromPast(@Param("itemId") Long itemId, @Param("today") LocalDate today);
}
