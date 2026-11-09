package com.yamu.be.benefitlimit;

import com.yamu.be.card.CardActivityReader;
import com.yamu.be.card.CardIssuers;
import com.yamu.be.card.domain.CardProduct;
import com.yamu.be.card.domain.CardProductRepository;
import com.yamu.be.card.domain.CardRegistrationStatus;
import com.yamu.be.card.domain.UserCard;
import com.yamu.be.card.domain.UserCardRepository;
import com.yamu.be.recommendation.BenefitPolicy;
import com.yamu.be.recommendation.domain.BenefitLimitLink;
import com.yamu.be.recommendation.domain.BenefitLimitLinkRepository;
import com.yamu.be.recommendation.domain.CardBenefit;
import com.yamu.be.recommendation.domain.CardBenefitRepository;
import com.yamu.be.recommendation.domain.MonthlyLimitUsage;
import com.yamu.be.recommendation.domain.MonthlyLimitUsageRepository;
import com.yamu.be.recommendation.domain.TierLimit;
import com.yamu.be.recommendation.domain.TierLimitRepository;
import java.text.NumberFormat;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 혜택 한도 현황(S15P31E103-374). 혜택·한도·구간은 BE 3 데이터라 읽기만 하고, 사용량은 결제 원장이 승인·취소 때 바꾸는
 * {@code monthly_limit_usage}, 지난달 실적은 {@code card_monthly_performance}를 읽는다. 판정 규칙은 {@link LimitJudge}.
 *
 * <p>2026-11-10 결정:
 * <ul>
 *   <li>월(MONTHLY) 한도만, 한도 그룹당 한 줄. 여러 혜택이 같이 쓰는 공유 한도도 한 줄이다. 건별·일별 한도와 한도 없는 혜택은
 *       이번 달 남은 한도가 아니라 뺀다.</li>
 *   <li>사용량 행이 없으면 0(S15P31E103-324), 지난달 실적 행이 없으면 0원.</li>
 *   <li>금액 합계는 원화 한도만. 횟수·포인트 한도는 상태만 판정하고 남은 한도는 null.</li>
 * </ul>
 *
 * <p>정책 버전: 오늘 유효할 수 있는 버전만 보고, 같은 혜택 코드·그룹 코드에 그런 버전이 둘 이상이면 현행을 모르므로 뺀다(카드
 * 목록의 {@code CardBenefitSummaryReader}, 추천 엔진과 같은 기준). 사용량 행은 그룹 버전별이다.
 */
@Slf4j
@Service
class BenefitLimitService {

    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Set<CardRegistrationStatus> REGISTERED =
            EnumSet.of(CardRegistrationStatus.ACTIVE, CardRegistrationStatus.INACTIVE);

    private final UserCardRepository cards;
    private final CardProductRepository products;
    private final CardActivityReader activity;
    private final CardBenefitRepository benefits;
    private final BenefitLimitLinkRepository links;
    private final LimitGroupReader groups;
    private final TierLimitRepository tiers;
    private final MonthlyLimitUsageRepository usages;
    private final Clock clock;

    BenefitLimitService(UserCardRepository cards, CardProductRepository products, CardActivityReader activity,
            CardBenefitRepository benefits, BenefitLimitLinkRepository links, LimitGroupReader groups,
            TierLimitRepository tiers, MonthlyLimitUsageRepository usages, Clock clock) {
        this.cards = cards;
        this.products = products;
        this.activity = activity;
        this.benefits = benefits;
        this.links = links;
        this.groups = groups;
        this.tiers = tiers;
        this.usages = usages;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public BenefitLimitsResponse limits(long userId) {
        LocalDate today = LocalDate.now(clock.withZone(KST));
        YearMonth month = YearMonth.from(today);
        List<UserCard> owned = cards.findOrdered(userId, REGISTERED);
        Map<Long, CardProduct> productById = products.findAllById(
                        owned.stream().map(UserCard::getCardProductId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(CardProduct::getId, Function.identity()));
        List<Long> cardIds = owned.stream().map(UserCard::getId).toList();

        Map<Long, List<Group>> groupsByProduct = monthlyGroups(productById.keySet(), today);
        Set<Long> groupIds = groupsByProduct.values().stream().flatMap(List::stream).map(g -> g.row().id())
                .collect(Collectors.toSet());
        Map<Long, Long> previous = activity.recognizedAmounts(cardIds, month.minusMonths(1));
        Map<String, Long> used = new HashMap<>();
        if (!cardIds.isEmpty() && !groupIds.isEmpty()) {
            for (MonthlyLimitUsage usage : usages.findByUserCardIdInAndLimitGroupIdInAndUsageMonth(cardIds, groupIds,
                    month.atDay(1))) {
                used.put(usage.getUserCardId() + ":" + usage.getLimitGroupId(), usage.getUsedValue());
            }
        }

        long receivedTotal = 0;
        long remainingTotal = 0;
        List<BenefitLimitsResponse.Card> items = new ArrayList<>();
        for (UserCard card : owned) {
            CardProduct product = productById.get(card.getCardProductId());
            long lastMonth = previous.getOrDefault(card.getId(), 0L);
            Long cardRemaining = null;
            List<BenefitLimitsResponse.Limit> limits = new ArrayList<>();
            for (Group group : groupsByProduct.getOrDefault(product.getId(), List.of())) {
                long usedValue = used.getOrDefault(card.getId() + ":" + group.row().id(), 0L);
                LimitJudge.Judgement judgement = LimitJudge.judge(group.rule(), lastMonth, usedValue).orElse(null);
                if (judgement == null) {
                    log.warn("혜택 한도 현황: 최대 한도를 정할 수 없어 뺌 product={} limitGroup={}", product.getId(),
                            group.row().id());
                    continue;
                }
                boolean krw = group.row().unit() == BenefitPolicy.Unit.KRW;
                if (krw) {
                    cardRemaining = (cardRemaining == null ? 0 : cardRemaining) + judgement.remaining();
                    receivedTotal += usedValue;
                    remainingTotal += judgement.remaining();
                }
                limits.add(new BenefitLimitsResponse.Limit(group.row().name(), krw ? judgement.remaining() : null,
                        judgement.status(), judgement.unlockThreshold() == null ? null
                                : "지난달 실적이 " + won(judgement.unlockThreshold()) + "에 못 미쳐요"));
            }
            items.add(new BenefitLimitsResponse.Card(card.getId(), nickname(card, product),
                    CardIssuers.of(product.getIssuerCode()), cardRemaining, limits));
        }
        return new BenefitLimitsResponse(month.toString(),
                new BenefitLimitsResponse.Summary(receivedTotal, remainingTotal), items, null);
    }

    /** 상품별로 오늘 유효한 혜택에 연결된 월 한도 그룹과 판정 규칙. 한도 그룹 번호 순. */
    private Map<Long, List<Group>> monthlyGroups(Collection<Long> productIds, LocalDate today) {
        if (productIds.isEmpty()) {
            return Map.of();
        }
        List<Long> productList = List.copyOf(productIds);
        Map<Long, CardBenefit> current = singleVersion(
                benefits.findPossiblyEffective(productList, today, BenefitPolicy.NO_END_DATE),
                b -> b.getCardProductId() + ":" + b.getBenefitCode(), "혜택").stream()
                .collect(Collectors.toMap(CardBenefit::getId, Function.identity()));
        if (current.isEmpty()) {
            return Map.of();
        }
        Map<Long, LimitGroupReader.Row> monthly = singleVersion(groups.possiblyEffective(productList, today),
                g -> g.productId() + ":" + g.groupCode(), "한도 그룹").stream()
                .filter(g -> g.period() == BenefitPolicy.Period.MONTHLY)
                .collect(Collectors.toMap(LimitGroupReader.Row::id, Function.identity()));
        // 그룹별로 연결된 오늘 유효한 혜택의 최소 전월 실적. 연결이 없는 그룹은 받을 혜택이 없어 뺀다
        Map<Long, List<Long>> minimums = new HashMap<>();
        for (BenefitLimitLink link : links.findByCardProductIdInAndCardBenefitIdIn(productList, current.keySet())) {
            CardBenefit benefit = current.get(link.getCardBenefitId());
            LimitGroupReader.Row group = monthly.get(link.getLimitGroupId());
            if (benefit != null && group != null && group.productId() == link.getCardProductId()) {
                minimums.computeIfAbsent(group.id(), k -> new ArrayList<>()).add(benefit.getMinPerformanceAmount());
            }
        }
        if (minimums.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<LimitJudge.Tier>> tiersByGroup = tiers.findByLimitGroupIdIn(minimums.keySet()).stream()
                .collect(Collectors.groupingBy(TierLimit::getLimitGroupId, Collectors.mapping(
                        t -> new LimitJudge.Tier(t.getMinPerformanceAmount(), t.getMaxPerformanceAmount(),
                                t.getLimitValue()), Collectors.toList())));
        return minimums.keySet().stream().sorted().map(monthly::get)
                .map(row -> new Group(row, new LimitJudge.Rule(row.baseLimitValue(),
                        tiersByGroup.getOrDefault(row.id(), List.of()), minimums.get(row.id()))))
                .collect(Collectors.groupingBy(g -> g.row().productId()));
    }

    private static <T> List<T> singleVersion(List<T> rows, Function<T, String> code, String kind) {
        List<T> result = new ArrayList<>();
        rows.stream().collect(Collectors.groupingBy(code)).forEach((key, versions) -> {
            if (versions.size() == 1) {
                result.add(versions.get(0));
            } else {
                log.warn("혜택 한도 현황: 오늘 유효할 수 있는 {} 버전이 {}개라 뺌 {}", kind, versions.size(), key);
            }
        });
        return result;
    }

    /** "30만원" · "1만 5,000원"처럼 만원 단위로. */
    static String won(long amount) {
        NumberFormat format = NumberFormat.getIntegerInstance(Locale.KOREA);
        long man = amount / 10_000;
        long rest = amount % 10_000;
        if (man == 0) {
            return format.format(rest) + "원";
        }
        return rest == 0 ? format.format(man) + "만원" : format.format(man) + "만 " + format.format(rest) + "원";
    }

    /** 카드 목록과 같다: 별칭이 없으면 상품명. */
    private static String nickname(UserCard card, CardProduct product) {
        return card.getCardAlias() == null ? product.getProductName() : card.getCardAlias();
    }

    private record Group(LimitGroupReader.Row row, LimitJudge.Rule rule) {
    }
}
