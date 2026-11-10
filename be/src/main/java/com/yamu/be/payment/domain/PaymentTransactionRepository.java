package com.yamu.be.payment.domain;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, Long> {

    /** 소유 확인을 겸한 조회. 남의 결제는 없는 것과 같다(FR-001). */
    Optional<PaymentTransaction> findByIdAndUserId(Long id, Long userId);

    Optional<PaymentTransaction> findByRequestKey(String requestKey);

    /**
     * 취소용 행 잠금(SELECT ... FOR UPDATE)과 소유 확인. 같은 결제의 취소가 동시에 오면 뒤 요청은 앞 요청이 끝날 때까지 기다렸다가
     * CANCELED를 읽는다(복구·환급 행이 두 번 생기지 않게).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PaymentTransaction p WHERE p.id = :id AND p.userId = :userId")
    Optional<PaymentTransaction> findForCancel(@Param("id") Long id, @Param("userId") Long userId);

    /**
     * 사용자 거절용 행 잠금과 소유 확인(S15P31E103-360). 같은 결제의 거절이 겹치면 뒤 요청은 앞 요청이 끝난 뒤 REJECTED를 읽는다.
     * 동시에 온 승인은 version이 어긋나 한쪽만 저장된다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PaymentTransaction p WHERE p.id = :id AND p.userId = :userId")
    Optional<PaymentTransaction> findForReject(@Param("id") Long id, @Param("userId") Long userId);

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
