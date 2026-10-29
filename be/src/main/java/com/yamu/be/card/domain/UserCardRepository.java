package com.yamu.be.card.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserCardRepository extends JpaRepository<UserCard, Long> {

    Optional<UserCard> findByIdAndUserId(Long id, Long userId);

    /** 카드번호 조회 해시로 이 사용자의 카드를 찾는다. (user_id, card_no_hash)는 UNIQUE라 많아야 하나다. */
    Optional<UserCard> findByUserIdAndCardNoHash(Long userId, byte[] cardNoHash);

    /** 보유 카드 목록 순서: 사용자가 정한 순서(없으면 뒤로) → 등록 순 → 번호 순. */
    @Query("""
            select c from UserCard c
            where c.userId = :userId and c.registrationStatus in :statuses
            order by case when c.displayOrder is null then 1 else 0 end, c.displayOrder, c.registeredAt, c.id
            """)
    List<UserCard> findOrdered(@Param("userId") Long userId,
            @Param("statuses") Collection<CardRegistrationStatus> statuses);
}
