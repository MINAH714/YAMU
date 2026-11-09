package com.yamu.be.recommendation.domain;

import java.util.Collection;
import java.util.List;
import org.springframework.data.repository.Repository;

public interface LimitGroupRepository extends Repository<LimitGroup, Long> {
    List<LimitGroup> findByCardProductIdIn(Collection<Long> products);
}