package com.yamu.be.recommendation.domain;

import java.util.Collection;
import java.util.List;
import org.springframework.data.repository.Repository;

public interface BenefitLimitLinkRepository extends Repository<BenefitLimitLink, Long> {
    List<BenefitLimitLink> findByCardProductIdInAndCardBenefitIdIn(Collection<Long> products, Collection<Long> benefits);
}