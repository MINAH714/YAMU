package com.yamu.be.payment.billing;

import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CardBillRepository extends JpaRepository<CardBill, Long> {

    java.util.List<CardBill> findByUserCardIdInAndStatus(java.util.Collection<Long> cardIds, BillStatus status);

    /** 같은 카드·같은 청구 월의 청구서는 하나다({@code uk_bill_card_month}). */
    Optional<CardBill> findByUserCardIdAndBillingMonth(Long userCardId, LocalDate billingMonth);

    /** 이 카드에 그 상태의 청구서가 있는지. 신용 승인이 미납(UNPAID) 카드를 거를 때 쓴다(FR-069). */
    boolean existsByUserCardIdAndStatus(Long userCardId, BillStatus status);

    /**
     * 청구 확정 대상: 이용기간 마감이 기준일 전(마감 &lt; 기준일, 즉 어제 이전)인 진행 중 청구서를 번호 순으로 한 쪽씩.
     * 하루 못 돈 날이 있어도 따라잡도록 "어제가 마감"이 아니라 "마감이 지난" 전부를 본다. userId가 있으면 그 사용자만(시연용).
     * 한계: (status, period_end) 인덱스가 없어 상태 인덱스({@code ix_bill_due}의 앞자리)로 거른 뒤 마감일은 행에서 본다.
     * 진행 중 청구서는 카드마다 한 달에 하나라 양이 적다.
     */
    @Query("SELECT b FROM CardBill b WHERE b.status = :status AND b.periodEnd < :before AND b.id > :lastId"
            + " AND (:userId IS NULL OR b.userId = :userId) ORDER BY b.id")
    List<CardBill> findDueForConfirm(@Param("status") BillStatus status, @Param("before") LocalDate before,
            @Param("userId") Long userId, @Param("lastId") long lastId, Pageable page);

    /**
     * 청구서 잠금(SELECT ... FOR UPDATE). 번호 순으로 잠가, 여러 청구서를 잠그는 작업끼리(취소, 청구 확정) 순서가 엇갈리지 않게 한다.
     * 빈 목록은 넘기지 않는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM CardBill b WHERE b.id IN :ids ORDER BY b.id")
    List<CardBill> findAllForUpdate(@Param("ids") Collection<Long> ids);
}
