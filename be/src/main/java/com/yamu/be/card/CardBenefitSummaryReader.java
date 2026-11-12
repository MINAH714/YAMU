package com.yamu.be.card;

import com.yamu.be.recommendation.BenefitPolicy;
import com.yamu.be.recommendation.domain.BenefitLimitLink;
import com.yamu.be.recommendation.domain.BenefitLimitLinkRepository;
import com.yamu.be.recommendation.domain.CardBenefit;
import com.yamu.be.recommendation.domain.CardBenefitRepository;
import com.yamu.be.recommendation.domain.LimitGroup;
import com.yamu.be.recommendation.domain.LimitGroupRepository;
import com.yamu.be.recommendation.domain.TierLimit;
import com.yamu.be.recommendation.domain.TierLimitRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 카드 목록에 보여 줄 상품별 주요 혜택 문구와 실적 기준(명세 V2 「보유 카드 목록 조회」의 {@code mainBenefits}·
 * {@code performance.targetAmount}). 혜택·한도·실적 구간은 BE 3 데이터라 BE 3 Repository로 읽기만 한다.
 *
 * <p>정책 버전: 오늘 유효할 수 있는 버전({@link CardBenefitRepository#findPossiblyEffective})만 본다. 같은 혜택 코드(한도는 같은
 * 그룹 코드)에 그런 버전이 둘 이상이면 어느 것이 현행인지 모르므로 화면에서 뺀다. 추천 엔진도 이 경우를 버전 불명으로 보고 쓰지
 * 않는다(버전 번호가 크다고 현행으로 추정하지 않음).
 *
 * <p>임시(규칙 미정, MR·Jira S15P31E103-308에 기록):
 * <ul>
 *   <li>주요 혜택: {@code benefit_name} 그대로. 필요한 전월 실적이 낮은 순, 혜택률이 높은 순(정액은 뒤), 혜택 코드 순으로
 *       최대 {@value #MAX_MAIN_BENEFITS}개. 명세 예시처럼 비율·한도를 붙인 문장은 만들지 않는다.</li>
 *   <li>실적 기준: 혜택의 최소 전월 실적과, 그 혜택에 연결된 한도의 실적 구간 하한 중 0원보다 큰 값.</li>
 * </ul>
 *
 * <p>카드별 실적 화면(S15P31E103-373, {@code performance} 패키지)도 같은 기준·구간을 쓰도록 공개한다. 두 화면의 목표 실적이
 * 달라지지 않게, 기준을 바꿀 때는 이 클래스만 고친다.
 */
@Component
@RequiredArgsConstructor
public class CardBenefitSummaryReader {

    static final int MAX_MAIN_BENEFITS = 3;

    private static final Comparator<CardBenefit> MAIN_ORDER = Comparator
            .comparing(CardBenefit::getMinPerformanceAmount, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(CardBenefitSummaryReader::rateOrNull, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(CardBenefit::getBenefitCode);

    private final CardBenefitRepository benefits;
    private final BenefitLimitLinkRepository links;
    private final LimitGroupRepository groups;
    private final TierLimitRepository tiers;

    /** @return 상품 번호별 요약. 오늘 유효한 혜택이 없는 상품은 빠진다 */
    public Map<Long, Summary> summaries(Collection<Long> productIds, LocalDate today) {
        if (productIds.isEmpty()) {
            return Map.of();
        }
        List<Long> products = productIds.stream().distinct().toList();
        List<CardBenefit> current = singleVersion(
                benefits.findPossiblyEffective(products, today, BenefitPolicy.NO_END_DATE),
                b -> b.getCardProductId() + ":" + b.getBenefitCode());
        if (current.isEmpty()) {
            return Map.of();
        }
        Map<Long, LimitGroup> currentGroupRows = singleVersion(groups.findByCardProductIdIn(products).stream()
                        .filter(g -> possiblyEffective(g.getValidFrom(), g.getValidUntil(), today)).toList(),
                g -> g.getCardProductId() + ":" + g.getGroupCode())
                .stream().collect(Collectors.toMap(LimitGroup::getId, g -> g));
        Set<Long> currentGroups = currentGroupRows.keySet();
        List<BenefitLimitLink> linkRows = links.findByCardProductIdInAndCardBenefitIdIn(products,
                current.stream().map(CardBenefit::getId).toList());
        Map<Long, Long> groupProduct = linkRows.stream()
                .filter(l -> currentGroups.contains(l.getLimitGroupId()))
                .collect(Collectors.toMap(BenefitLimitLink::getLimitGroupId, BenefitLimitLink::getCardProductId,
                        (a, b) -> a));
        List<TierLimit> tierRows = groupProduct.isEmpty() ? List.of() : tiers.findByLimitGroupIdIn(groupProduct.keySet());

        Map<Long, List<CardBenefit>> byProduct = current.stream()
                .collect(Collectors.groupingBy(CardBenefit::getCardProductId));
        Map<Long, Summary> result = new HashMap<>();
        byProduct.forEach((product, rows) -> {
            List<String> names = rows.stream().sorted(MAIN_ORDER).map(CardBenefit::getBenefitName)
                    .filter(Objects::nonNull).distinct().limit(MAX_MAIN_BENEFITS).toList();
            TreeSet<Long> thresholds = new TreeSet<>();
            rows.stream().map(CardBenefit::getMinPerformanceAmount).filter(a -> a != null && a > 0)
                    .forEach(thresholds::add);
            tierRows.stream().filter(t -> product.equals(groupProduct.get(t.getLimitGroupId())))
                    .map(TierLimit::getMinPerformanceAmount).filter(a -> a != null && a > 0)
                    .forEach(thresholds::add);
            List<Tier> tierList = thresholds.stream().map(threshold -> tier(threshold, rows, linkRows,
                    currentGroupRows, tierRows)).toList();
            result.put(product, new Summary(names, List.copyOf(thresholds), tierList,
                    baseMonthlyKrwLimit(rows, linkRows, currentGroupRows, tierRows)));
        });
        return result;
    }

    /**
     * 실적 기준 하나의 구간 정보(S15P31E103-373, 임시 2026-11-10). 혜택 이름은 그 기준에서 새로 열리는 혜택(최소 전월 실적이
     * 기준과 같은 혜택), 월 최대치는 실적이 기준과 같을 때 한 달에 받을 수 있는 원화 혜택의 최대치({@link TierLimitCap})다.
     *
     * <p>그룹의 한도 값은 추천 엔진({@code RecommendationInputAssembler.maximum})과 같게 읽는다: 구간 행이 없으면 기본 한도,
     * 있으면 기준이 속한 구간 하나의 값. 기본 한도와 구간이 함께 있거나, 구간이 겹치거나, 기준이 속한 구간이 없거나, 버전을 몰라
     * 화면에서 뺀 그룹에 걸린 혜택이 있으면 모르는 값으로 본다.
     */
    private static Tier tier(long threshold, List<CardBenefit> benefitRows, List<BenefitLimitLink> linkRows,
            Map<Long, LimitGroup> groupRows, List<TierLimit> tierRows) {
        List<String> opened = benefitRows.stream()
                .filter(b -> b.getMinPerformanceAmount() != null && b.getMinPerformanceAmount() == threshold)
                .sorted(MAIN_ORDER).map(CardBenefit::getBenefitName).filter(Objects::nonNull).distinct().toList();
        // 기준 실적에서 쓸 수 있는 혜택(최소 전월 실적 ≤ 기준)
        Map<Long, Set<Long>> groupsOfBenefit = new HashMap<>();
        benefitRows.stream().filter(b -> b.getMinPerformanceAmount() == null || b.getMinPerformanceAmount() <= threshold)
                .forEach(b -> groupsOfBenefit.put(b.getId(), new java.util.HashSet<>()));
        Map<Long, Long> groupValue = new HashMap<>();
        for (BenefitLimitLink link : linkRows) {
            if (!groupsOfBenefit.containsKey(link.getCardBenefitId())) {
                continue;
            }
            LimitGroup group = groupRows.get(link.getLimitGroupId());
            if (group == null) {
                // 버전을 모르거나 오늘 유효하지 않은 한도에 걸린 혜택: 상한을 모른다
                return new Tier(threshold, opened, null);
            }
            if (group.getPeriodType() != BenefitPolicy.Period.MONTHLY || group.getLimitUnit() != BenefitPolicy.Unit.KRW) {
                continue;
            }
            groupsOfBenefit.get(link.getCardBenefitId()).add(group.getId());
            groupValue.put(group.getId(), limitAt(group, tierRows, threshold));
        }
        return new Tier(threshold, opened, TierLimitCap.monthlyMax(groupsOfBenefit, groupValue));
    }

    /**
     * 전월 실적 0원일 때 한 달에 받을 수 있는 원화 혜택의 최대치(S15P31E103-375, 2026-11-12). 실적 조건이 없는 혜택(예: 노리2
     * 스타벅스·커피빈 10%)은 실적을 채우지 않아도 받으므로, 유불리 분석이 "채우면 더 받는 혜택"에서 뺀다. 계산은 구간과 같은
     * {@link #tier}다. 0원에서 쓸 수 있는 혜택이 없으면 0, 한도를 모르면 null.
     */
    private static Long baseMonthlyKrwLimit(List<CardBenefit> benefitRows, List<BenefitLimitLink> linkRows,
            Map<Long, LimitGroup> groupRows, List<TierLimit> tierRows) {
        boolean anyWithoutPerformance = benefitRows.stream()
                .anyMatch(b -> b.getMinPerformanceAmount() == null || b.getMinPerformanceAmount() <= 0);
        if (!anyWithoutPerformance) {
            return 0L;
        }
        // 삼항 연산자로 0L과 섞으면 null 한도가 언박싱돼 NPE가 나므로 나눠 쓴다
        return tier(0, benefitRows, linkRows, groupRows, tierRows).monthlyKrwLimit();
    }

    /** 실적이 기준과 같을 때 그룹의 한도 값. 모르면 null(추천 엔진과 같은 판단). */
    private static Long limitAt(LimitGroup group, List<TierLimit> tierRows, long threshold) {
        List<TierLimit> rows = tierRows.stream().filter(t -> t.getLimitGroupId().equals(group.getId()))
                .sorted(Comparator.comparing(TierLimit::getMinPerformanceAmount)).toList();
        if (rows.isEmpty()) {
            return group.getBaseLimitValue();
        }
        if (group.getBaseLimitValue() != null) {
            return null;
        }
        for (int i = 1; i < rows.size(); i++) {
            Long end = rows.get(i - 1).getMaxPerformanceAmount();
            if (end == null || end > rows.get(i).getMinPerformanceAmount()) {
                return null;
            }
        }
        List<TierLimit> matched = rows.stream().filter(t -> threshold >= t.getMinPerformanceAmount()
                && (t.getMaxPerformanceAmount() == null || threshold < t.getMaxPerformanceAmount())).toList();
        return matched.size() == 1 ? matched.get(0).getLimitValue() : null;
    }

    private static <T> List<T> singleVersion(List<T> rows, java.util.function.Function<T, String> code) {
        return rows.stream().collect(Collectors.groupingBy(code)).values().stream()
                .filter(versions -> versions.size() == 1).map(versions -> versions.get(0)).toList();
    }

    /** {@code findPossiblyEffective}와 같은 기준. 시작·종료일 미확인(NULL)은 막지 않는다. */
    private static boolean possiblyEffective(LocalDate from, LocalDate until, LocalDate date) {
        return (from == null || !date.isBefore(from))
                && (until == null || until.equals(BenefitPolicy.NO_END_DATE) || date.isBefore(until));
    }

    private static BigDecimal rateOrNull(CardBenefit benefit) {
        return benefit.getCalculationType() == BenefitPolicy.CalculationType.RATE ? benefit.getRate() : null;
    }

    /**
     * @param mainBenefits 주요 혜택 문구. 최대 {@value #MAX_MAIN_BENEFITS}개
     * @param thresholds   실적 기준(원), 작은 순. 없으면 빈 목록
     * @param tiers        기준별 구간 정보. {@code thresholds}와 같은 순서·개수
     * @param baseMonthlyKrwLimit 전월 실적 0원일 때 한 달 원화 혜택 최대치(실적 조건 없는 혜택). 그런 혜택이 없으면 0, 모르면 null
     */
    public record Summary(List<String> mainBenefits, List<Long> thresholds, List<Tier> tiers,
            Long baseMonthlyKrwLimit) {

        /** 이번 달 실적보다 큰 기준 중 가장 작은 값. 다 넘었으면 가장 큰 기준, 기준이 없으면 null. */
        public Long targetFor(long recognized) {
            if (thresholds.isEmpty()) {
                return null;
            }
            return thresholds.stream().filter(t -> t > recognized).findFirst()
                    .orElse(thresholds.get(thresholds.size() - 1));
        }
    }

    /**
     * 실적 구간 하나.
     *
     * @param threshold       실적 기준(원, 포함)
     * @param benefitNames    이 기준에서 새로 열리는 혜택 이름. 한도만 커지는 기준이면 빈 목록
     * @param monthlyKrwLimit 실적이 기준과 같을 때 한 달에 받을 수 있는 원화 혜택의 최대치({@link TierLimitCap}). 겹친 한도는
     *                        한 번만 센다. 원화·월 한도가 없는 혜택이 있거나 한도 값을 모르면 null
     */
    public record Tier(long threshold, List<String> benefitNames, Long monthlyKrwLimit) {
    }
}
