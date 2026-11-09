package com.yamu.be.cashflow;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface CashflowCreateRequestRepository extends JpaRepository<CashflowCreateRequest, Long> {
    /** 이 사용자가 그 Idempotency-Key로 보낸 등록 기록. */
    Optional<CashflowCreateRequest> findByUserIdAndRequestKey(Long userId, String requestKey);
}
