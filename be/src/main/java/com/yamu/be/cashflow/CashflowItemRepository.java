package com.yamu.be.cashflow;

import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CashflowItemRepository extends JpaRepository<CashflowItem, Long> {
    @Query("""
            select item from CashflowItem item
            where item.userId = :userId and item.itemKind = :kind
              and item.scheduledDate <= :to
              and (item.recurrenceType <> :none
                   or item.scheduledDate >= :from)
              and (item.recurrenceEndDate is null or item.recurrenceEndDate >= :from)
              and (:direction is null or item.direction = :direction)
              and item.status = :status
            order by item.scheduledDate, item.id
            """)
    List<CashflowItem> findRulesForUser(@Param("userId") Long userId,
            @Param("from") LocalDate from, @Param("to") LocalDate to,
            @Param("kind") CashflowItem.ItemKind kind,
            @Param("none") CashflowItem.RecurrenceType none,
            @Param("direction") CashflowItem.Direction direction,
            @Param("status") CashflowItem.Status status);

    /** 시리즈의 첫 규칙을 찾을 때 쓴다. 잠그기 전이라 엔티티를 영속성 컨텍스트에 올리지 않고 ID만 읽는다. */
    @Query("""
            select item.id as id, item.previousCashflowItemId as previousCashflowItemId
            from CashflowItem item where item.id = :id and item.userId = :userId
            """)
    Optional<Link> findLink(@Param("id") Long id, @Param("userId") Long userId);

    /** 규칙 행을 SELECT … FOR UPDATE로 잠그고 읽는다. 트랜잭션 안에서만 부를 수 있다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select item from CashflowItem item where item.id = :id and item.userId = :userId")
    Optional<CashflowItem> findForUpdate(@Param("id") Long id, @Param("userId") Long userId);

    interface Link {
        Long getId();

        Long getPreviousCashflowItemId();
    }
}
