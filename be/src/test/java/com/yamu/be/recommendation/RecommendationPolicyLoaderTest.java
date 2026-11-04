package com.yamu.be.recommendation;

import static com.yamu.be.recommendation.RecommendationRows.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.yamu.be.recommendation.domain.*;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.test.util.ReflectionTestUtils;

class RecommendationPolicyLoaderTest {
    CardBenefitRepository benefits = mock(CardBenefitRepository.class);
    BenefitTargetRepository targets = mock(BenefitTargetRepository.class);
    BenefitLimitLinkRepository links = mock(BenefitLimitLinkRepository.class);
    LimitGroupRepository groups = mock(LimitGroupRepository.class);
    TierLimitRepository tiers = mock(TierLimitRepository.class);
    MonthlyLimitUsageRepository monthly = mock(MonthlyLimitUsageRepository.class);
    PaymentLimitUsageRepository daily = mock(PaymentLimitUsageRepository.class);
    RecommendationPolicyLoader loader = new RecommendationPolicyLoader(benefits, targets, links, groups, tiers, monthly, daily,
            mock(BenefitTargetGroupRepository.class), mock(BenefitTargetGroupMemberRepository.class),
            mock(BenefitApplicationGroupRepository.class), mock(BenefitApplicationMemberRepository.class), mock(PurchaseItemRepository.class));

    @Test
    void batchesSharedProductsAndGroupsAndQueriesOnlyTheCorrespondingPeriod() {
        var date = LocalDate.of(2026, 10, 15);
        when(benefits.findPossiblyEffective(List.of(10L), date, BenefitPolicy.NO_END_DATE)).thenReturn(List.of(benefit(1), benefit(2)));
        when(links.findByCardProductIdInAndCardBenefitIdIn(List.of(10L), List.of(1L, 2L)))
                .thenReturn(List.of(link(1), link(2)));
        var dayGroup = group();
        ReflectionTestUtils.setField(dayGroup, "id", 101L);
        ReflectionTestUtils.setField(dayGroup, "periodType", BenefitPolicy.Period.DAILY);
        // 연결되지 않은 일별 그룹은 조회하지 않는다.
        when(groups.findByCardProductIdIn(List.of(10L))).thenReturn(List.of(group(), dayGroup));
        var result = loader.load(1, List.of(card(1), card(2)), date);
        assertThat(result.benefits()).hasSize(2);
        verify(benefits, times(1)).findPossiblyEffective(List.of(10L), date, BenefitPolicy.NO_END_DATE);
        verify(targets, times(1)).findByCardBenefitIdIn(List.of(1L, 2L));
        verify(tiers, times(1)).findByLimitGroupIdIn(List.of(100L));
        verify(monthly, times(1)).findByUserCardIdInAndLimitGroupIdInAndUsageMonth(List.of(1L, 2L), List.of(100L), date.withDayOfMonth(1));
        verifyNoInteractions(daily);
    }

    @Test
    void emptyCardsNeverProduceEmptyInQueries() {
        assertThat(loader.load(1, List.of(), LocalDate.of(2026, 10, 1))).isEqualTo(empty());
        verifyNoInteractions(benefits, targets, links, groups, tiers, monthly, daily);
    }

    @Test
    void normalEmptyDailyQueryReachesEngineLimitInputAsZeroButTimeoutDoesNotReturnData() {
        var date = LocalDate.of(2026, 10, 15);
        var group = group();
        ReflectionTestUtils.setField(group, "periodType", BenefitPolicy.Period.DAILY);
        when(benefits.findPossiblyEffective(List.of(10L), date, BenefitPolicy.NO_END_DATE)).thenReturn(List.of(benefit(1)));
        when(links.findByCardProductIdInAndCardBenefitIdIn(List.of(10L), List.of(1L))).thenReturn(List.of(link(1)));
        when(groups.findByCardProductIdIn(List.of(10L))).thenReturn(List.of(group));
        when(daily.sumDaily(1, List.of(1L), List.of(100L), date, date.withDayOfMonth(1))).thenReturn(List.of());

        var data = loader.load(1, List.of(card(1)), date);
        var policy = new RecommendationInputAssembler(new ObjectMapper()).policy(card(1), data, date, null, new ArrayList<>());
        assertThat(policy.limits()).singleElement().satisfies(l -> assertThat(l.used()).isZero());
        verifyNoInteractions(monthly);

        var failure = new QueryTimeoutException("daily query timed out");
        when(daily.sumDaily(1, List.of(1L), List.of(100L), date, date.withDayOfMonth(1))).thenThrow(failure);
        assertThatThrownBy(() -> loader.load(1, List.of(card(1)), date)).isSameAs(failure);
    }
}
