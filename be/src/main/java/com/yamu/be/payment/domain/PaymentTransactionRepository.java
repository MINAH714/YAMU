package com.yamu.be.payment.domain;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, Long> {

    /** 소유 확인을 겸한 조회. 남의 결제는 없는 것과 같다(FR-001). */
    Optional<PaymentTransaction> findByIdAndUserId(Long id, Long userId);

    Optional<PaymentTransaction> findByRequestKey(String requestKey);

    /**
     * 결과 불명 복구 대상: 한동안 바뀌지 않은 PROCESSING 결제를 결제 번호 순으로 50건씩. afterId보다 큰 번호부터 읽어
     * 판정하지 못한 행이 앞에 쌓여도 뒤의 결제까지 본다.
     */
    List<PaymentTransaction> findTop50ByStatusAndUpdatedAtBeforeAndIdGreaterThanOrderByIdAsc(PaymentStatus status,
            LocalDateTime updatedBefore, Long afterId);

    /** 늦은 출금 대조 대상: 그 사유로 거절·만료한 뒤 얼마 안 된 결제를 결제 번호 순으로 50건씩. */
    List<PaymentTransaction> findTop50ByStatusInAndFailureCodeAndUpdatedAtAfterAndIdGreaterThanOrderByIdAsc(
            Collection<PaymentStatus> statuses, String failureCode, LocalDateTime updatedAfter, Long afterId);
}
