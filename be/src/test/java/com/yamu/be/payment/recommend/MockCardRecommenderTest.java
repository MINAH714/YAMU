package com.yamu.be.payment.recommend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.yamu.be.card.domain.CardIssuerStatus;
import com.yamu.be.card.domain.CardType;
import com.yamu.be.payment.PaymentTerms;
import com.yamu.be.payment.lookup.PaymentCardLookup;
import com.yamu.be.payment.lookup.PaymentCardLookup.ActiveCard;
import com.yamu.be.payment.recommend.RecommendationRequest.MerchantContext;
import com.yamu.be.payment.recommend.RecommendationResult.Candidate;
import com.yamu.be.payment.recommend.RecommendationResult.ExclusionReason;
import com.yamu.be.payment.recommend.RecommendationResult.Status;
import com.yamu.be.payment.recommend.RecommendationResult.TieBreak;
import com.yamu.be.payment.recommend.RecommendationResult.UnavailableReason;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MockCardRecommenderTest {

    static final long PAYMENT = 1024L;
    static final long USER = 7L;
    static final Instant NOW = Instant.parse("2026-10-31T15:30:00Z");
    static final MerchantContext CAFE = new MerchantContext(301L, "F01-CAFE", "OFFLINE", "GENERAL");

    PaymentCardLookup lookup;
    MockCardRecommender recommender;

    @BeforeEach
    void setUp() {
        lookup = mock(PaymentCardLookup.class);
        recommender = new MockCardRecommender(lookup, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    // ───────── 순위 ─────────

    @Test
    void 기본_카드가_1순위이고_동률_규칙은_DEFAULT_CARD() {
        given(card(1, CardType.CREDIT).displayOrder(0),
                card(2, CardType.CHECK).defaultCard());

        RecommendationResult result = recommender.recommend(lumpSum(15_000));

        assertThat(result.candidates()).extracting(c -> c.card().userCardId()).containsExactly(2L, 1L);
        assertThat(result.candidates()).extracting(Candidate::rank).containsExactly(1, 2);
        assertThat(result.topCandidate().orElseThrow().tieBreak()).isEqualTo(TieBreak.DEFAULT_CARD);
        assertThat(result.candidates().get(1).tieBreak()).isNull();
    }

    @Test
    void 기본_카드가_없으면_표시_순서_그다음_순서_없는_카드_그다음_카드_번호이고_FIXED_ORDER() {
        given(card(30, CardType.CREDIT),
                card(20, CardType.CREDIT),
                card(10, CardType.CREDIT).displayOrder(5),
                card(40, CardType.CREDIT).displayOrder(2));

        RecommendationResult result = recommender.recommend(lumpSum(15_000));

        assertThat(result.candidates()).extracting(c -> c.card().userCardId()).containsExactly(40L, 10L, 20L, 30L);
        assertThat(result.topCandidate().orElseThrow().tieBreak()).isEqualTo(TieBreak.FIXED_ORDER);
    }

    @Test
    void 후보가_한_장이면_동률이_아니라_tieBreak가_없다() {
        given(card(1, CardType.CREDIT));

        RecommendationResult result = recommender.recommend(lumpSum(15_000));

        assertThat(result.candidates()).hasSize(1);
        assertThat(result.topCandidate().orElseThrow().tieBreak()).isNull();
    }

    // ───────── 제외 ─────────

    @Test
    void 카드사_정지_만료_카드는_사유와_함께_뺀다() {
        given(card(1, CardType.CREDIT),
                card(2, CardType.CREDIT).issuer(CardIssuerStatus.SUSPENDED),
                card(3, CardType.CREDIT).issuer(CardIssuerStatus.EXPIRED));

        RecommendationResult result = recommender.recommend(lumpSum(15_000));

        assertThat(result.candidates()).extracting(c -> c.card().userCardId()).containsExactly(1L);
        assertThat(result.excluded(2L).orElseThrow().reason()).isEqualTo(ExclusionReason.CARD_SUSPENDED);
        assertThat(result.excluded(3L).orElseThrow().reason()).isEqualTo(ExclusionReason.CARD_EXPIRED);
        assertThat(result.candidate(2L)).isEmpty();
        // 모의 추천은 혜택·결제 가능 여부를 판정하지 않아 미비교 카드를 내지 않는다
        assertThat(result.uncomparedCards()).isEmpty();
        assertThat(result.status()).isEqualTo(Status.READY);
    }

    @Test
    void 할부면_체크카드를_빼고_일시불이면_넣는다() {
        given(card(1, CardType.CREDIT), card(2, CardType.CHECK));

        RecommendationResult installment = recommender.recommend(request(PaymentTerms.installment(100_000, 3)));
        assertThat(installment.candidates()).extracting(c -> c.card().userCardId()).containsExactly(1L);
        assertThat(installment.excluded(2L).orElseThrow().reason())
                .isEqualTo(ExclusionReason.INSTALLMENT_NOT_SUPPORTED);

        RecommendationResult lumpSum = recommender.recommend(lumpSum(100_000));
        assertThat(lumpSum.candidates()).extracting(c -> c.card().userCardId()).containsExactly(1L, 2L);
        assertThat(lumpSum.excludedCards()).isEmpty();
    }

    // ───────── 후보 없음 ─────────

    @Test
    void 결제_불가_사유로_후보가_없으면_NO_ELIGIBLE_CARD() {
        given(card(1, CardType.CHECK), card(2, CardType.CREDIT).issuer(CardIssuerStatus.SUSPENDED));

        RecommendationResult result = recommender.recommend(request(PaymentTerms.installment(100_000, 3)));

        assertThat(result.isAvailable()).isFalse();
        assertThat(result.status()).isEqualTo(Status.NO_ELIGIBLE_CARD);
        assertThat(result.unavailableReason()).isEqualTo(UnavailableReason.NO_ELIGIBLE_CARD);
        assertThat(result.topCandidate()).isEmpty();
        assertThat(result.excludedCards()).hasSize(2);
    }

    @Test
    void ACTIVE_카드가_없으면_NO_ELIGIBLE_CARD() {
        given();

        RecommendationResult result = recommender.recommend(lumpSum(15_000));

        assertThat(result.status()).isEqualTo(Status.NO_ELIGIBLE_CARD);
        assertThat(result.unavailableReason()).isEqualTo(UnavailableReason.NO_ELIGIBLE_CARD);
        assertThat(result.excludedCards()).isEmpty();
    }

    // ───────── 값 ─────────

    @Test
    void 후보는_혜택_0원_즉시할인_0원_규칙_버전은_0이고_조회한_카드_정보를_그대로_담는다() {
        given(card(1, CardType.CREDIT).defaultCard());

        Candidate top = recommender.recommend(lumpSum(15_000)).topCandidate().orElseThrow();

        assertThat(top.netBenefit()).isZero();
        assertThat(top.benefits()).isEmpty();
        // 결제할 금액은 결제 쪽이 PaymentTerms.chargedAmount로 계산한다
        assertThat(top.instantDiscount()).isZero();
        assertThat(top.reason()).isEqualTo(MockCardRecommender.REASON_PLACEHOLDER);
        assertThat(top.ruleVersion().cardProductId()).isEqualTo(100L);
        assertThat(top.ruleVersion().version()).isEqualTo(MockCardRecommender.MOCK_RULE_VERSION);
        assertThat(top.card().cardType()).isEqualTo(CardType.CREDIT);
        assertThat(top.card().defaultCard()).isTrue();
        assertThat(top.card().alias()).isEqualTo("카드1");
        assertThat(top.card().last4()).isEqualTo("0001");
        assertThat(top.card().issuerCode()).isEqualTo("TEST");
        assertThat(top.card().productName()).isEqualTo("상품 100");
    }

    @Test
    void 계산_시각은_시계_기준_UTC이고_추천_식별자는_rec_와_16진수_12자리() {
        given(card(1, CardType.CREDIT));

        RecommendationResult result = recommender.recommend(lumpSum(15_000));

        assertThat(result.calculatedAt()).isEqualTo(LocalDateTime.of(2026, 10, 31, 15, 30));
        assertThat(result.recommendationId()).matches("rec_[0-9a-f]{12}");
        assertThat(result.estimate()).isFalse();
    }

    // ───────── 도우미 ─────────

    void given(CardBuilder... cards) {
        when(lookup.findActiveCards(USER)).thenReturn(List.of(cards).stream().map(CardBuilder::build).toList());
    }

    static RecommendationRequest lumpSum(long amount) {
        return request(PaymentTerms.lumpSum(amount));
    }

    static RecommendationRequest request(PaymentTerms terms) {
        return new RecommendationRequest(PAYMENT, USER, terms, CAFE, LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
    }

    static CardBuilder card(long userCardId, CardType type) {
        return new CardBuilder(userCardId, type);
    }

    static final class CardBuilder {
        final long userCardId;
        final CardType type;
        CardIssuerStatus issuer = CardIssuerStatus.NORMAL;
        boolean defaultCard;
        Integer displayOrder;

        CardBuilder(long userCardId, CardType type) {
            this.userCardId = userCardId;
            this.type = type;
        }

        CardBuilder issuer(CardIssuerStatus issuer) {
            this.issuer = issuer;
            return this;
        }

        CardBuilder defaultCard() {
            this.defaultCard = true;
            return this;
        }

        CardBuilder displayOrder(int order) {
            this.displayOrder = order;
            return this;
        }

        ActiveCard build() {
            long product = 100 + userCardId - 1;
            return new ActiveCard(userCardId, product, type, issuer, defaultCard, displayOrder, "카드" + userCardId,
                    String.format("%04d", userCardId), "TEST", "상품 " + product);
        }
    }
}
