package com.yamu.be.card.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserCardRepository extends JpaRepository<UserCard, Long> {

    Optional<UserCard> findByIdAndUserId(Long id, Long userId);

    /** 카드번호 조회 해시로 이 사용자의 카드를 찾는다. (user_id, card_no_hash)는 UNIQUE라 많아야 하나다. */
    Optional<UserCard> findByUserIdAndCardNoHash(Long userId, byte[] cardNoHash);
}
