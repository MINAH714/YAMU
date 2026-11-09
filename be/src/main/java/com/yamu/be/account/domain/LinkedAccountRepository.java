package com.yamu.be.account.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LinkedAccountRepository extends JpaRepository<LinkedAccount, Long> {

    List<LinkedAccount> findByIdInAndUserId(Collection<Long> ids, Long userId);

    Optional<LinkedAccount> findByIdAndUserId(Long id, Long userId);

    boolean existsByUserIdAndStatus(Long userId, LinkedAccountStatus status);

    /** 연결 후보 목록에서 이미 연결된 계좌를 표시·제외할 때 쓴다. */
    List<LinkedAccount> findByUserIdAndStatus(Long userId, LinkedAccountStatus status);

    /** 같은 사람의 같은 계좌(uk_account_identity). 끊은 계좌도 찾는다. */
    Optional<LinkedAccount> findByUserIdAndBankCodeAndAccountNoHash(Long userId, String bankCode, byte[] accountNoHash);

    /** 계좌 목록. 연결한 순서. */
    List<LinkedAccount> findByUserIdAndStatusOrderByIdAsc(Long userId, LinkedAccountStatus status);

    /** 잔액 조회 대상 확인. 남의 계좌·해제한 계좌는 빠진다. */
    List<LinkedAccount> findByIdInAndUserIdAndStatus(Collection<Long> ids, Long userId, LinkedAccountStatus status);

    /** 사용자의 계좌 전부(끊은 계좌 포함). 탈퇴 때 쓴다. */
    List<LinkedAccount> findByUserId(Long userId);

    /** 지금 기준 계좌. 사용자당 최대 1개(uk_account_primary_owner). */
    List<LinkedAccount> findByUserIdAndPrimaryTrue(Long userId);
}
