package com.yamu.be.payment.billing;

import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CardBillRepository extends JpaRepository<CardBill, Long> {

    java.util.List<CardBill> findByUserCardIdInAndStatus(java.util.Collection<Long> cardIds, BillStatus status);

    /** 같은 카드·같은 청구 월의 청구서는 하나다({@code uk_bill_card_month}). */
    Optional<CardBill> findByUserCardIdAndBillingMonth(Long userCardId, LocalDate billingMonth);

    /**
     * 이 카드에 남은 미납액이 있는 미납(UNPAID) 청구서가 있는지. 신용 승인이 미납 카드를 거를 때 쓴다(FR-069).
     * 미납 결제를 모두 취소해 남은 미납액(확정액 − 출금액 − 취소로 뺀 미납 원금)이 0이면 청구서는 UNPAID로 남아도 거르지 않는다.
     * 확정액이 비어 있으면 남은 미납액을 알 수 없으므로 미납으로 본다.
     */
    @Query("""
            SELECT COUNT(b) > 0 FROM CardBill b
            WHERE b.userCardId = :userCardId AND b.status = :status
              AND (b.confirmedAmount IS NULL OR b.confirmedAmount - b.paidAmount - b.canceledUnpaidPrincipal > 0)
            """)
    boolean existsOutstandingUnpaid(@Param("userCardId") Long userCardId, @Param("status") BillStatus status);

    /**
     * 청구서 잠금(SELECT ... FOR UPDATE). 번호 순으로 잠가, 여러 청구서를 잠그는 작업끼리(취소, 청구 확정) 순서가 엇갈리지 않게 한다.
     * 빈 목록은 넘기지 않는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM CardBill b WHERE b.id IN :ids ORDER BY b.id")
    List<CardBill> findAllForUpdate(@Param("ids") Collection<Long> ids);
}
