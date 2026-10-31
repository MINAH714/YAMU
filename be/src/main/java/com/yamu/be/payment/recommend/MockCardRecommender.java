package com.yamu.be.payment.recommend;

import com.yamu.be.card.domain.CardIssuerStatus;
import com.yamu.be.payment.PaymentTerms;
import com.yamu.be.payment.lookup.PaymentCardLookup;
import com.yamu.be.payment.lookup.PaymentCardLookup.ActiveCard;
import com.yamu.be.payment.recommend.RecommendationResult.Candidate;
import com.yamu.be.payment.recommend.RecommendationResult.CardSummary;
import com.yamu.be.payment.recommend.RecommendationResult.ExcludedCard;
import com.yamu.be.payment.recommend.RecommendationResult.ExclusionReason;
import com.yamu.be.payment.recommend.RecommendationResult.RuleVersion;
import com.yamu.be.payment.recommend.RecommendationResult.TieBreak;
import com.yamu.be.payment.recommend.RecommendationResult.UnavailableReason;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * BE3 추천 엔진이 들어오기 전까지 쓰는 가짜 추천. 결제 요청·승인 흐름을 개발·시험할 수 있게 후보 순서만 만든다.
 *
 * <p>하는 것: ACTIVE 카드를 후보로 돌려준다. 카드사 정지·만료 카드, 할부 결제의 체크카드(POL-013), 혜택 자료가 없는 상품은 뺀다.
 * 혜택은 모두 0원이라 전부 동률이고, 순위는 동률 규칙(DEC-004) 중 기본 카드 → 고정 정렬(표시 순서, 없으면 뒤 → 카드 번호)만 쓴다.
 *
 * <p>하지 않는 것(임시, BE3 구현이 맡는다): 혜택·한도·실적 계산, 미납 청구·신용 가용 한도·체크카드 잔액 확인.
 * 모의 신용한도는 초기값이 미정(POL-013)이라 보지 않는다.
 *
 * <p>후보가 없을 때(임시 규칙): 혜택 자료가 없어 뺀 카드가 있으면 DATA_INSUFFICIENT, 아니면 NO_ELIGIBLE_CARD.
 */
public class MockCardRecommender implements CardRecommender {

    static final String ID_PREFIX = "rec_";
    static final int ID_HEX_LENGTH = 12;
    static final String REASON_PLACEHOLDER = "모의 추천: 혜택 계산 전";
    /** 혜택 규칙을 읽지 않았다는 표시. 실제 규칙 버전은 1부터다. */
    static final int MOCK_RULE_VERSION = 0;

    private static final Comparator<ActiveCard> FIXED_ORDER = Comparator
            .comparing(ActiveCard::defaultCard, Comparator.reverseOrder())
            .thenComparing(ActiveCard::displayOrder, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(ActiveCard::userCardId);

    private final PaymentCardLookup cards;
    private final Clock clock;

    public MockCardRecommender(PaymentCardLookup cards, Clock clock) {
        this.cards = cards;
        this.clock = clock;
    }

    @Override
    public RecommendationResult recommend(RecommendationRequest request) {
        PaymentTerms terms = request.terms();
        List<ActiveCard> active = new ArrayList<>(cards.findActiveCards(request.userId()));
        active.sort(FIXED_ORDER);

        List<Candidate> candidates = new ArrayList<>();
        List<ExcludedCard> excluded = new ArrayList<>();
        for (ActiveCard card : active) {
            ExclusionReason reason = exclusionReason(card, terms);
            if (reason != null) {
                excluded.add(new ExcludedCard(summary(card), reason, message(reason)));
                continue;
            }
            candidates.add(new Candidate(candidates.size() + 1, summary(card), 0, List.of(), terms.amount(),
                    REASON_PLACEHOLDER, null, new RuleVersion(card.cardProductId(), MOCK_RULE_VERSION)));
        }
        // 전부 혜택 0원이라 후보가 둘 이상이면 1순위는 동률 규칙으로 정해진 것이다.
        if (candidates.size() > 1) {
            Candidate top = candidates.get(0);
            TieBreak tieBreak = top.card().defaultCard() ? TieBreak.DEFAULT_CARD : TieBreak.FIXED_ORDER;
            candidates.set(0, new Candidate(top.rank(), top.card(), top.netBenefit(), top.benefits(),
                    top.chargedAmount(), top.reason(), tieBreak, top.ruleVersion()));
        }
        UnavailableReason unavailable = candidates.isEmpty() ? unavailableReason(excluded) : null;
        return new RecommendationResult(newId(), LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC), false,
                candidates, excluded, unavailable);
    }

    private static ExclusionReason exclusionReason(ActiveCard card, PaymentTerms terms) {
        if (card.issuerStatus() == CardIssuerStatus.SUSPENDED) {
            return ExclusionReason.CARD_SUSPENDED;
        }
        if (card.issuerStatus() == CardIssuerStatus.EXPIRED) {
            return ExclusionReason.CARD_EXPIRED;
        }
        if (!terms.allows(card.cardType())) {
            return ExclusionReason.INSTALLMENT_NOT_SUPPORTED;
        }
        if (card.benefitCount() == 0) {
            return ExclusionReason.BENEFIT_DATA_MISSING;
        }
        return null;
    }

    private static UnavailableReason unavailableReason(List<ExcludedCard> excluded) {
        boolean dataMissing = excluded.stream().anyMatch(e -> e.reason() == ExclusionReason.BENEFIT_DATA_MISSING);
        return dataMissing ? UnavailableReason.DATA_INSUFFICIENT : UnavailableReason.NO_ELIGIBLE_CARD;
    }

    private static String message(ExclusionReason reason) {
        return switch (reason) {
            case CARD_SUSPENDED -> "카드사에서 정지한 카드예요";
            case CARD_EXPIRED -> "유효기간이 지난 카드예요";
            case INSTALLMENT_NOT_SUPPORTED -> "체크카드는 할부로 결제할 수 없어요";
            case BENEFIT_DATA_MISSING -> "이 카드의 혜택 정보가 아직 없어요";
            default -> reason.name();
        };
    }

    private static CardSummary summary(ActiveCard card) {
        return new CardSummary(card.userCardId(), card.cardProductId(), card.cardType(), card.defaultCard(),
                card.alias(), card.last4(), card.issuerCode(), card.productName());
    }

    private static String newId() {
        return ID_PREFIX + UUID.randomUUID().toString().replace("-", "").substring(0, ID_HEX_LENGTH);
    }
}
