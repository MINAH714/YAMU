package com.yamu.be.account.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LinkedAccountRepository extends JpaRepository<LinkedAccount, Long> {

    Optional<LinkedAccount> findByIdAndUserId(Long id, Long userId);

    boolean existsByUserIdAndStatus(Long userId, LinkedAccountStatus status);
}
