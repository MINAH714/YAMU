package com.yamu.be.recommendation.domain;

import java.util.Collection;
import java.util.List;
import org.springframework.data.repository.Repository;

public interface BenefitTargetRepository extends Repository<BenefitTarget, Long> {
    List<BenefitTarget> findByCardBenefitIdIn(Collection<Long> ids);
}