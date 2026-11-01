package com.yamu.be.payment.domain;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, Long> {

    /** 소유 확인을 겸한 조회. 남의 결제는 없는 것과 같다(FR-001). */
    Optional<PaymentTransaction> findByIdAndUserId(Long id, Long userId);

    Optional<PaymentTransaction> findByRequestKey(String requestKey);

    /** 결과 불명 복구 대상: 한동안 바뀌지 않은 PROCESSING 결제. 오래된 것부터 최대 50건(주기 작업 한 번 분량). */
    List<PaymentTransaction> findTop50ByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(PaymentStatus status,
            LocalDateTime updatedBefore);
}
