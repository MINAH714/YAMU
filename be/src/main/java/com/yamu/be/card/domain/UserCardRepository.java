package com.yamu.be.card.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserCardRepository extends JpaRepository<UserCard, Long> {

    Optional<UserCard> findByIdAndUserId(Long id, Long userId);
}
