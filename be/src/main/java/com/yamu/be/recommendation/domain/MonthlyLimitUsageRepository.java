package com.yamu.be.recommendation.domain;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import org.springframework.data.repository.Repository;

public interface MonthlyLimitUsageRepository extends Repository<MonthlyLimitUsage, Long> {
    List<MonthlyLimitUsage> findByUserCardIdInAndLimitGroupIdInAndUsageMonth(
            Collection<Long> cards, Collection<Long> groups, LocalDate month);
}
