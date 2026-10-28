package com.yamu.be.cashflow;

import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
