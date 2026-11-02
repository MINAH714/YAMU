package com.yamu.be.payment.billing;

import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CardBillRepository extends JpaRepository<CardBill, Long> {

    /** 같은 카드·같은 청구 월의 청구서는 하나다({@code uk_bill_card_month}). */
    Optional<CardBill> findByUserCardIdAndBillingMonth(Long userCardId, LocalDate billingMonth);

    /** 소유 확인을 겸한 조회. 남의 청구서는 없는 것과 같다(FR-001). */
    Optional<CardBill> findByIdAndUserId(Long id, Long userId);
}
