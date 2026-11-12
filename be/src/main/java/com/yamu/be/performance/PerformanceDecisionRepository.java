package com.yamu.be.performance;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PerformanceDecisionRepository extends JpaRepository<PerformanceDecision, Long> {

    Optional<PerformanceDecision> findByUserIdAndUserCardIdAndPerformanceMonth(Long userId, Long userCardId,
            LocalDate performanceMonth);

    List<PerformanceDecision> findByUserIdAndUserCardIdInAndPerformanceMonth(Long userId, Collection<Long> userCardIds,
            LocalDate performanceMonth);

    /**
     * 판단 기록 전에 사용자의 카드 행을 잠가(SELECT … FOR UPDATE) 같은 카드의 기록 요청을 한 줄로 세운다. 첫 기록이 동시에 와도
     * 한 줄만 만들어진다(UNIQUE에 걸려 500이 나지 않게). 남의 카드면 아무 행도 잠그지 않고 빈 목록을 돌려준다.
     */
    @Query(value = "SELECT user_card_id FROM user_card WHERE user_card_id = :userCardId AND user_id = :userId"
            + " FOR UPDATE", nativeQuery = true)
    List<Long> lockOwnedCard(@Param("userId") Long userId, @Param("userCardId") Long userCardId);
}
