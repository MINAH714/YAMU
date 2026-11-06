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
 */
@Component
@RequiredArgsConstructor
class CardBenefitSummaryReader {

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
    Map<Long, Summary> summaries(Collection<Long> productIds, LocalDate today) {
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
        Set<Long> currentGroups = singleVersion(groups.findByCardProductIdIn(products).stream()
                        .filter(g -> possiblyEffective(g.getValidFrom(), g.getValidUntil(), today)).toList(),
                g -> g.getCardProductId() + ":" + g.getGroupCode())
                .stream().map(LimitGroup::getId).collect(Collectors.toSet());
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
            result.put(product, new Summary(names, List.copyOf(thresholds)));
        });
        return result;
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
     */
    record Summary(List<String> mainBenefits, List<Long> thresholds) {

        /** 이번 달 실적보다 큰 기준 중 가장 작은 값. 다 넘었으면 가장 큰 기준, 기준이 없으면 null. */
        Long targetFor(long recognized) {
            if (thresholds.isEmpty()) {
                return null;
            }
            return thresholds.stream().filter(t -> t > recognized).findFirst()
                    .orElse(thresholds.get(thresholds.size() - 1));
        }
    }
}
