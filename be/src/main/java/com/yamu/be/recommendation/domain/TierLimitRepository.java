package com.yamu.be.recommendation.domain;

import java.util.Collection;
import java.util.List;
import org.springframework.data.repository.Repository;

public interface TierLimitRepository extends Repository<TierLimit, Long> {
    List<TierLimit> findByLimitGroupIdIn(Collection<Long> groups);
}