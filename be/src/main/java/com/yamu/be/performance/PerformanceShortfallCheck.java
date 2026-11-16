package com.yamu.be.performance;

import com.yamu.be.card.CardActivityReader;
import com.yamu.be.card.CardBenefitSummaryReader;
import com.yamu.be.card.domain.CardProduct;
import com.yamu.be.card.domain.CardProductRepository;
import com.yamu.be.card.domain.CardRegistrationStatus;
import com.yamu.be.card.domain.UserCard;
import com.yamu.be.card.domain.UserCardRepository;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 실적 부족 알림(Task 9.3.3, S15P31E103-465)이 쓰는 판정. 이번 달 첫 구간(가장 작은 실적 기준)을 못 채울 것 같은 카드를 고른다.
 * 실적을 새로 계산하지 않고 카드별 실적 현황(S15P31E103-373)의 {@link PerformanceService#progress}를 그대로 쓴다.
 *
 * <ul>
 *   <li>첫 구간만(2026-11-13 결정): 첫 구간을 넘은 카드는 목표가 다음 구간이 되어 AT_RISK일 수 있지만 고르지 않는다. 다음 구간까지 알리면
 *       "더 써서 다음 구간으로"로 읽혀 더 쓰라고 권하지 않는다는 원칙(FR-032)과 부딪힌다. 첫 구간 아래면 목표가 곧 첫 구간이다.</li>
 *   <li>ACTIVE 카드만: INACTIVE는 추천에서 뺀, 결제에 쓰지 않는 카드다.</li>
 *   <li>이번 달만: 지난달 미달은 이미 끝난 결과라 사전 알림 대상이 아니다. 첫 5일은 373 규칙대로 ON_TRACK이라 고르지 않는다.</li>
 *   <li>인정액을 확인 중인 결제가 있는 카드는 {@link Shortfall#provisional()}로 표시만 한다. 실적이 낮게 잡혔을 수 있어 알릴지는 부른 쪽이 정한다.</li>
 *   <li>실적 판단(S15P31E103-375)에서 이번 달 이 카드의 실적 부족 알림을 멈춘 카드({@code performance_decision.notifications_muted})는
 *       {@link Shortfall#muted()}로 표시한다. 고르는 기준은 같고, 알리지 않는 것은 부른 쪽이 한다(결과에 건너뛴 이유를 남기도록).</li>
 * </ul>
 */
@Component
public class PerformanceShortfallCheck {

    private static final Set<CardRegistrationStatus> ACTIVE = EnumSet.of(CardRegistrationStatus.ACTIVE);

    private final UserCardRepository cards;
    private final CardProductRepository products;
    private final CardActivityReader activity;
    private final CardBenefitSummaryReader benefitSummaries;
    private final PerformanceReader reader;
    private final PerformanceDecisionRepository decisions;

    PerformanceShortfallCheck(UserCardRepository cards, CardProductRepository products, CardActivityReader activity,
            CardBenefitSummaryReader benefitSummaries, PerformanceReader reader,
            PerformanceDecisionRepository decisions) {
        this.cards = cards;
        this.products = products;
        this.activity = activity;
        this.benefitSummaries = benefitSummaries;
        this.reader = reader;
        this.decisions = decisions;
    }

    /**
     * @param today 한국 날짜 오늘. 이 날이 속한 달의 실적을 본다
     * @return 첫 구간을 못 채울 것 같은 카드, 카드 목록 순서
     */
    @Transactional(readOnly = true)
    public List<Shortfall> shortfalls(long userId, LocalDate today) {
        List<UserCard> owned = cards.findOrdered(userId, ACTIVE);
        if (owned.isEmpty()) {
            return List.of();
        }
        YearMonth month = YearMonth.from(today);
        Set<Long> productIds = owned.stream().map(UserCard::getCardProductId).collect(Collectors.toSet());
        Map<Long, CardProduct> productById = products.findAllById(productIds).stream()
                .collect(Collectors.toMap(CardProduct::getId, Function.identity()));
        List<Long> ids = owned.stream().map(UserCard::getId).toList();
        Map<Long, Long> recognized = activity.recognizedAmounts(ids, month);
        Map<Long, CardBenefitSummaryReader.Summary> summaries = benefitSummaries.summaries(productIds, today);
        Set<Long> provisional = reader.cardsWithUnconfirmed(userId, ids, month);
        Set<Long> muted = mutedCards(userId, ids, month);
        int daysLeft = month.lengthOfMonth() - today.getDayOfMonth();

        List<Shortfall> found = new ArrayList<>();
        for (UserCard card : owned) {
            CardBenefitSummaryReader.Summary summary = summaries.get(card.getCardProductId());
            if (summary == null || summary.thresholds().isEmpty()) {
                continue;
            }
            long first = summary.thresholds().get(0);
            PerformanceService.Progress progress =
                    PerformanceService.progress(summary, recognized.get(card.getId()), month, today);
            if (progress.status() != PerformanceStatus.AT_RISK || progress.recognized() >= first) {
                continue;
            }
            CardProduct product = productById.get(card.getCardProductId());
            found.add(new Shortfall(card.getId(), PerformanceService.nickname(card, product), first,
                    PerformanceService.label(first), progress.recognized(), progress.remaining(), daysLeft,
                    provisional.contains(card.getId()), muted.contains(card.getId())));
        }
        return found;
    }

    /** 그달 실적 판단에서 실적 부족 알림을 멈춘 카드. 판단을 되돌리면 멈춤도 풀려 false다. */
    private Set<Long> mutedCards(long userId, List<Long> ids, YearMonth month) {
        return decisions.findByUserIdAndUserCardIdInAndPerformanceMonth(userId, ids, month.atDay(1)).stream()
                .filter(PerformanceDecision::isNotificationsMuted)
                .map(PerformanceDecision::getUserCardId)
                .collect(Collectors.toSet());
    }

    /**
     * 첫 구간을 못 채울 것 같은 카드 한 장.
     *
     * @param cardName    별칭, 없으면 상품명
     * @param target      첫 구간 기준(원)
     * @param targetLabel "30만원 이상"처럼 실적 화면과 같은 표기
     * @param remaining   첫 구간까지 남은 금액(원, 0보다 큼)
     * @param daysLeft    이번 달 남은 날(오늘 뺌). 말일이면 0
     * @param provisional 그달 인정액이 확인되지 않은 승인 결제가 있는지
     * @param muted       그달 실적 판단에서 이 카드의 실적 부족 알림을 멈췄는지
     */
    public record Shortfall(long userCardId, String cardName, long target, String targetLabel, long recognized,
            long remaining, int daysLeft, boolean provisional, boolean muted) {
    }
}
