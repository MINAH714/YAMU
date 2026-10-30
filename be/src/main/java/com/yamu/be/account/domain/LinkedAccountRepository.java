package com.yamu.be.account.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LinkedAccountRepository extends JpaRepository<LinkedAccount, Long> {

    Optional<LinkedAccount> findByIdAndUserId(Long id, Long userId);

    boolean existsByUserIdAndStatus(Long userId, LinkedAccountStatus status);

    /** 연결 후보 목록에서 이미 연결된 계좌를 표시·제외할 때 쓴다. */
    List<LinkedAccount> findByUserIdAndStatus(Long userId, LinkedAccountStatus status);

    /** 같은 사람의 같은 계좌(uk_account_identity). 끊은 계좌도 찾는다. */
    Optional<LinkedAccount> findByUserIdAndBankCodeAndAccountNoHash(Long userId, String bankCode, byte[] accountNoHash);

    /** 가장 먼저 연결한 계좌. 기준 계좌(isPrimary)를 따로 저장하기 전까지 이것을 기준 계좌로 본다. */
    Optional<LinkedAccount> findFirstByUserIdAndStatusOrderByIdAsc(Long userId, LinkedAccountStatus status);
}
