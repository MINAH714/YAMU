package com.yamu.be.performance;

import com.yamu.be.card.CardBenefitSummaryReader;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 유불리 분석의 목표 실적·구간을 카드별 실적 현황(S15P31E103-373)과 같은 {@link CardBenefitSummaryReader}(BE1 -308 임시 규칙)로
 * 읽는다. 목표는 {@link CardBenefitSummaryReader.Summary#targetFor}, 구간의 원화 월 한도는
 * {@link CardBenefitSummaryReader.Tier#monthlyKrwLimit}(모르면 null)라 실적 화면과 분석의 목표·구간이 달라지지 않는다.
 */
@Component
class BenefitSummaryPerformanceTargets implements PerformanceTargets {

    private final CardBenefitSummaryReader summaries;

    BenefitSummaryPerformanceTargets(CardBenefitSummaryReader summaries) {
        this.summaries = summaries;
    }

    @Override
    public Optional<PerformanceAnalyzer.Target> targetFor(long cardProductId, long recognized, LocalDate today) {
        CardBenefitSummaryReader.Summary summary = summaries.summaries(List.of(cardProductId), today).get(cardProductId);
        if (summary == null) {
            return Optional.empty();
        }
        Long target = summary.targetFor(recognized);
        if (target == null) {
            return Optional.empty();
        }
        return Optional.of(new PerformanceAnalyzer.Target(target, summary.tiers().stream()
                .map(t -> new PerformanceAnalyzer.Tier(t.threshold(), t.monthlyKrwLimit(), t.benefitNames()))
                .toList()));
    }
}
