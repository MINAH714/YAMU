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
import com.yamu.be.payment.recommend.RecommendationResult.UncomparedReason;
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
 * <p>하는 것: ACTIVE 카드를 모두 혜택 0원 후보로 돌려준다. 결제 불가인 카드사 정지·만료 카드와 할부 결제의 체크카드(POL-013)만 뺀다.
 * 전부 동률이라 순위는 동률 규칙 중 기본 카드 → 고정 정렬(표시 순서, 없으면 뒤 → 카드 번호)만 쓴다.
 *
 * <p>하지 않는 것(BE3 구현이 맡는다): 혜택·한도·실적 계산, 혜택 자료 유무 판정, 미납 청구·신용 가용 한도·체크카드 잔액 확인.
 * 그래서 미비교 카드({@link UncomparedReason})를 내지 않고 결과는 READY 또는 NO_ELIGIBLE_CARD뿐이다. 모의 신용한도는 초기값이 미정(POL-013)이라 보지 않는다.
 *
 * <p>주의(임시): 혜택을 계산하지 않은 카드를 0원 후보로 순위에 넣는 것은 2026-11-02 정책 변경("혜택 미확인 카드를 0원 후보로 계산하지 않음")과 다르다.
 * 결제 흐름을 개발·시연할 수 있게 둔 모의 동작이고(후보 사유 {@link #REASON_PLACEHOLDER}, 규칙 버전 {@link #MOCK_RULE_VERSION}으로 표시),
 * BE3 엔진이 연결되면 빠진다. 그 전에 모두 미비교(혜택 미확인)로 바꿀지는 담당자와 정한다.
 */
public class MockCardRecommender implements CardRecommender {

    static final String ID_PREFIX = "rec_";
    static final int ID_HEX_LENGTH = 12;
    static final String REASON_PLACEHOLDER = "모의 추천: 혜택 계산 전";
    /** 혜택 규칙을 읽지 않았다는 표시. 실제 규칙 버전은 1부터다. */
    static final int MOCK_RULE_VERSION = 0;

    /** 고정 정렬. 표시 순서(display_order)를 기준으로 둔 것은 임시다(명세 10/31판의 권장안은 카드 번호 오름차순, BE1 마이그레이션은 표시 순서). */
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
        return new RecommendationResult(newId(), LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC), false,
                candidates, excluded, List.of());
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
        return null;
    }

    private static String message(ExclusionReason reason) {
        return switch (reason) {
            case CARD_SUSPENDED -> "카드사에서 정지한 카드예요";
            case CARD_EXPIRED -> "유효기간이 지난 카드예요";
            case INSTALLMENT_NOT_SUPPORTED -> "체크카드는 할부로 결제할 수 없어요";
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
