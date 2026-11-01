package com.yamu.be.payment.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, Long> {

    /** 소유 확인을 겸한 조회. 남의 결제는 없는 것과 같다(FR-001). */
    Optional<PaymentTransaction> findByIdAndUserId(Long id, Long userId);

    Optional<PaymentTransaction> findByRequestKey(String requestKey);
}
