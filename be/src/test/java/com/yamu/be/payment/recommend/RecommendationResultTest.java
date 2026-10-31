package com.yamu.be.payment.recommend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yamu.be.card.domain.CardType;
import com.yamu.be.payment.recommend.RecommendationResult.Candidate;
import com.yamu.be.payment.recommend.RecommendationResult.CardSummary;
import com.yamu.be.payment.recommend.RecommendationResult.ExcludedCard;
import com.yamu.be.payment.recommend.RecommendationResult.ExclusionReason;
import com.yamu.be.payment.recommend.RecommendationResult.RuleVersion;
import com.yamu.be.payment.recommend.RecommendationResult.UnavailableReason;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 추천 결과 계약. 후보가 없는 결과(데이터 부족·결제 가능 카드 없음)를 받는 쪽이 기대할 수 있는 모양을 고정한다. */
class RecommendationResultTest {

    static final LocalDateTime AT = LocalDateTime.of(2026, 10, 31, 15, 30);
    static final CardSummary CARD_1 = new CardSummary(1L, 100L, CardType.CREDIT, false, null, "0001", "TEST", "상품");
    static final CardSummary CARD_2 = new CardSummary(2L, 101L, CardType.CHECK, false, null, "0002", "TEST", "상품");

    @Test
    void 데이터_부족이면_후보도_1순위도_없고_결제_불가_카드만_남는다() {
        RecommendationResult result = new RecommendationResult("rec_000000000001", AT, false, List.of(),
                List.of(new ExcludedCard(CARD_2, ExclusionReason.CARD_SUSPENDED, "정지")),
                UnavailableReason.DATA_INSUFFICIENT);

        assertThat(result.isAvailable()).isFalse();
        assertThat(result.topCandidate()).isEmpty();
        assertThat(result.candidate(1L)).isEmpty();
        assertThat(result.excluded(2L).orElseThrow().reason()).isEqualTo(ExclusionReason.CARD_SUSPENDED);
    }

    @Test
    void 후보가_없으면_사유가_있어야_하고_후보가_있으면_사유가_없어야_한다() {
        assertThatThrownBy(() -> new RecommendationResult("rec_000000000001", AT, false, List.of(), List.of(), null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new RecommendationResult("rec_000000000001", AT, false, List.of(candidate(1)),
                List.of(), UnavailableReason.NO_ELIGIBLE_CARD))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 후보가_있으면_1순위가_첫_번째이고_카드_번호로_찾을_수_있다() {
        RecommendationResult result = new RecommendationResult("rec_000000000001", AT, false,
                List.of(candidate(1), new Candidate(2, CARD_2, 0, List.of(), 15_000, "", null, new RuleVersion(101L, 1))),
                List.of(), null);

        assertThat(result.isAvailable()).isTrue();
        assertThat(result.topCandidate().orElseThrow().card().userCardId()).isEqualTo(1L);
        assertThat(result.candidate(2L).orElseThrow().rank()).isEqualTo(2);
        assertThat(result.candidate(3L)).isEmpty();
        assertThat(result.excluded(1L)).isEmpty();
    }

    private static Candidate candidate(int rank) {
        return new Candidate(rank, CARD_1, 0, List.of(), 15_000, "", null, new RuleVersion(100L, 1));
    }
}
