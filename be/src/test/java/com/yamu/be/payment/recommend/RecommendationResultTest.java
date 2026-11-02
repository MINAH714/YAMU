package com.yamu.be.payment.recommend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.yamu.be.card.domain.CardType;
import com.yamu.be.payment.recommend.RecommendationResult.BenefitLine;
import com.yamu.be.payment.recommend.RecommendationResult.Candidate;
import com.yamu.be.payment.recommend.RecommendationResult.CardStatus;
import com.yamu.be.payment.recommend.RecommendationResult.CardSummary;
import com.yamu.be.payment.recommend.RecommendationResult.ExcludedCard;
import com.yamu.be.payment.recommend.RecommendationResult.ExclusionReason;
import com.yamu.be.payment.recommend.RecommendationResult.RuleVersion;
import com.yamu.be.payment.recommend.RecommendationResult.Status;
import com.yamu.be.payment.recommend.RecommendationResult.UnavailableReason;
import com.yamu.be.payment.recommend.RecommendationResult.UncomparedCard;
import com.yamu.be.payment.recommend.RecommendationResult.UncomparedReason;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 추천 결과 계약. 혜택 비교 가능 여부(후보·미비교)와 결제 가능 여부(제외·미비교 사유)를 나눈 모양과 상태 판정을 고정한다
 * (2026-11-02 정책 변경).
 */
class RecommendationResultTest {

    static final LocalDateTime AT = LocalDateTime.of(2026, 10, 31, 15, 30);
    static final CardSummary CARD_1 = card(1L, CardType.CREDIT);
    static final CardSummary CARD_2 = card(2L, CardType.CHECK);
    static final CardSummary CARD_3 = card(3L, CardType.CREDIT);
    static final CardSummary CARD_4 = card(4L, CardType.CHECK);

    // ───────── 상태 ─────────

    @Test
    void READY는_확인된_후보만_있고_1순위가_있다() {
        RecommendationResult result = result(List.of(candidate(1, CARD_1, 500), candidate(2, CARD_2, 0)), List.of(),
                List.of());

        assertThat(result.status()).isEqualTo(Status.READY);
        assertThat(result.isAvailable()).isTrue();
        assertThat(result.unavailableReason()).isNull();
        assertThat(result.topCandidate().orElseThrow().card().userCardId()).isEqualTo(1L);
        assertThat(result.candidate(2L).orElseThrow().rank()).isEqualTo(2);
    }

    @Test
    void PARTIAL_READY는_확인된_후보_중_1순위이고_미비교_카드는_순위가_없다() {
        RecommendationResult result = result(List.of(candidate(1, CARD_1, 0)), List.of(),
                List.of(uncompared(CARD_2, UncomparedReason.BENEFIT_DATA_INSUFFICIENT)));

        assertThat(result.status()).isEqualTo(Status.PARTIAL_READY);
        assertThat(result.isAvailable()).isTrue();
        assertThat(result.unavailableReason()).isNull();
        assertThat(result.topCandidate().orElseThrow().card().userCardId()).isEqualTo(1L);
        // 혜택 미확인 카드는 0원 후보로 순위에 들어가지 않는다
        assertThat(result.candidate(2L)).isEmpty();
        assertThat(result.uncompared(2L)).isPresent();
    }

    @Test
    void DATA_INSUFFICIENT는_확인된_후보가_없고_미비교_카드가_있으며_1순위가_없다() {
        RecommendationResult result = result(List.of(),
                List.of(new ExcludedCard(CARD_3, ExclusionReason.CARD_SUSPENDED, "정지")),
                List.of(uncompared(CARD_1, UncomparedReason.BENEFIT_DATA_INSUFFICIENT),
                        uncompared(CARD_2, UncomparedReason.PAYMENT_AVAILABILITY_UNKNOWN)));

        assertThat(result.status()).isEqualTo(Status.DATA_INSUFFICIENT);
        assertThat(result.isAvailable()).isFalse();
        assertThat(result.unavailableReason()).isEqualTo(UnavailableReason.DATA_INSUFFICIENT);
        assertThat(result.topCandidate()).isEmpty();
        // 1순위는 없어도 결제 가능이 확인되고 혜택만 미확인인 카드는 직접 골라 결제할 수 있다
        assertThat(result.uncompared(1L).orElseThrow().directPaymentAllowed()).isTrue();
        assertThat(result.uncompared(2L).orElseThrow().directPaymentAllowed()).isFalse();
    }

    @Test
    void 결제_가능_여부만_미확인인_카드도_DATA_INSUFFICIENT다() {
        RecommendationResult result = result(List.of(), List.of(),
                List.of(uncompared(CARD_2, UncomparedReason.PAYMENT_AVAILABILITY_UNKNOWN)));

        assertThat(result.status()).isEqualTo(Status.DATA_INSUFFICIENT);
        assertThat(result.uncomparedCards()).noneMatch(UncomparedCard::directPaymentAllowed);
    }

    @Test
    void NO_ELIGIBLE_CARD는_확인된_사유로_결제할_수_있는_카드가_없을_때다() {
        RecommendationResult onlyExcluded = result(List.of(),
                List.of(new ExcludedCard(CARD_2, ExclusionReason.CHECK_CARD_INSTALLMENT_NOT_SUPPORTED, "할부 불가")), List.of());
        RecommendationResult noCards = result(List.of(), List.of(), List.of());

        assertThat(onlyExcluded.status()).isEqualTo(Status.NO_ELIGIBLE_CARD);
        assertThat(onlyExcluded.unavailableReason()).isEqualTo(UnavailableReason.NO_ELIGIBLE_CARD);
        assertThat(onlyExcluded.excluded(2L).orElseThrow().reason()).isEqualTo(ExclusionReason.CHECK_CARD_INSTALLMENT_NOT_SUPPORTED);
        assertThat(noCards.status()).isEqualTo(Status.NO_ELIGIBLE_CARD);
        assertThat(noCards.isAvailable()).isFalse();
    }

    // ───────── 혜택 미확인과 결제 가능 미확인 ─────────

    @Test
    void 미비교_사유로_혜택만_미확인인지_결제_가능_여부가_미확인인지_나눈다() {
        UncomparedCard benefitUnknown = uncompared(CARD_1, UncomparedReason.BENEFIT_DATA_INSUFFICIENT);
        UncomparedCard paymentUnknown = uncompared(CARD_2, UncomparedReason.PAYMENT_AVAILABILITY_UNKNOWN);

        assertThat(benefitUnknown.directPaymentAllowed()).isTrue();
        assertThat(benefitUnknown.message()).isEqualTo("혜택 정보를 확인하지 못해 비교에서 제외했어요.");
        assertThat(paymentUnknown.directPaymentAllowed()).isFalse();
        assertThat(paymentUnknown.message()).isEqualTo("결제 가능 여부를 확인하지 못해 비교에서 제외했어요.");
        assertThat(RecommendationResult.NO_BENEFIT_NOTICE).isEqualTo("혜택 정보를 확인할 수 없어 혜택 없이 결제됩니다.");
    }

    @Test
    void 미비교_카드_문구를_따로_주면_그_문구를_쓴다() {
        UncomparedCard card = new UncomparedCard(CARD_1, UncomparedReason.PAYMENT_AVAILABILITY_UNKNOWN,
                "연결 계좌 잔액을 조회하지 못했어요.");

        assertThat(card.message()).isEqualTo("연결 계좌 잔액을 조회하지 못했어요.");
    }

    @Test
    void 확인된_0원_후보만_noBenefit이고_미비교_카드는_0원으로_세지_않는다() {
        RecommendationResult allZero = result(List.of(candidate(1, CARD_1, 0)), List.of(),
                List.of(uncompared(CARD_2, UncomparedReason.BENEFIT_DATA_INSUFFICIENT)));
        RecommendationResult someBenefit = result(List.of(candidate(1, CARD_1, 300), candidate(2, CARD_3, 0)),
                List.of(), List.of());
        RecommendationResult onlyUncompared = result(List.of(), List.of(),
                List.of(uncompared(CARD_2, UncomparedReason.BENEFIT_DATA_INSUFFICIENT)));

        assertThat(allZero.noBenefit()).isTrue();
        assertThat(someBenefit.noBenefit()).isFalse();
        // 미비교 카드뿐이면 "모두 0원"이 아니다
        assertThat(onlyUncompared.noBenefit()).isFalse();
    }

    @Test
    void cardStatus는_카드가_있는_목록을_알려주고_결과에_없는_카드는_비어_있다() {
        RecommendationResult result = result(List.of(candidate(1, CARD_1, 0)),
                List.of(new ExcludedCard(CARD_3, ExclusionReason.UNPAID_BILL, "미납")),
                List.of(uncompared(CARD_2, UncomparedReason.BENEFIT_DATA_INSUFFICIENT)));

        assertThat(result.cardStatus(1L)).contains(CardStatus.CALCULATED);
        assertThat(result.cardStatus(3L)).contains(CardStatus.EXCLUDED);
        assertThat(result.cardStatus(2L)).contains(CardStatus.NOT_COMPARED);
        assertThat(result.cardStatus(4L)).isEmpty();
    }

    // ───────── 잘못 만든 결과 ─────────

    @Test
    void 한_카드가_두_목록에_있으면_거절한다() {
        assertThatThrownBy(() -> result(List.of(candidate(1, CARD_1, 0)), List.of(),
                List.of(uncompared(CARD_1, UncomparedReason.BENEFIT_DATA_INSUFFICIENT))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> result(List.of(),
                List.of(new ExcludedCard(CARD_2, ExclusionReason.CARD_SUSPENDED, "정지")),
                List.of(uncompared(CARD_2, UncomparedReason.PAYMENT_AVAILABILITY_UNKNOWN))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 후보_순위가_1부터_차례가_아니면_거절한다() {
        assertThatThrownBy(() -> result(List.of(candidate(2, CARD_1, 0)), List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> result(List.of(candidate(1, CARD_1, 0), candidate(3, CARD_2, 0)), List.of(),
                List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 미비교_사유가_없으면_거절한다() {
        assertThatThrownBy(() -> new UncomparedCard(CARD_1, null, null)).isInstanceOf(NullPointerException.class);
    }

    // ───────── 스냅샷(JSON) ─────────

    @Test
    void 미비교_칸이_없고_chargedAmount가_있던_옛_스냅샷도_읽는다() throws Exception {
        String old = """
                {"recommendationId":"rec_000000000001","calculatedAt":"2026-10-31T15:30:00","estimate":false,
                 "candidates":[{"rank":1,"card":{"userCardId":1,"cardProductId":101,"cardType":"CREDIT",
                   "defaultCard":false,"alias":null,"last4":"0001","issuerCode":"TEST","productName":"상품"},
                   "netBenefit":0,"benefits":[],"chargedAmount":15000,"reason":"","tieBreak":null,
                   "ruleVersion":{"cardProductId":101,"version":1}}],
                 "excludedCards":[],"unavailableReason":null,"available":true}
                """;

        RecommendationResult result = json().readValue(old, RecommendationResult.class);

        assertThat(result.uncomparedCards()).isEmpty();
        assertThat(result.status()).isEqualTo(Status.READY);
        assertThat(result.topCandidate().orElseThrow().netBenefit()).isZero();
    }

    // ───────── 즉시할인 ─────────

    @Test
    void 즉시할인은_AT_APPROVAL_혜택만_더한다() {
        Candidate candidate = new Candidate(1, CARD_1, 2_500,
                List.of(new BenefitLine(1L, "즉시할인", "INSTANT_DISCOUNT", 750, 750, BenefitLine.AT_APPROVAL),
                        new BenefitLine(2L, "즉시할인2", "INSTANT_DISCOUNT", 250, 250, BenefitLine.AT_APPROVAL),
                        new BenefitLine(3L, "청구할인", "BILLING_DISCOUNT", 1_500, 1_500, "AT_BILLING")),
                "", null, new RuleVersion(101L, 1));

        assertThat(candidate.instantDiscount()).isEqualTo(1_000);
    }

    @Test
    void 미비교_카드는_스냅샷을_거쳐도_사유가_그대로다() throws Exception {
        ObjectMapper json = json();
        RecommendationResult result = result(List.of(), List.of(),
                List.of(uncompared(CARD_1, UncomparedReason.BENEFIT_DATA_INSUFFICIENT),
                        uncompared(CARD_2, UncomparedReason.PAYMENT_AVAILABILITY_UNKNOWN)));

        RecommendationResult read = json.readValue(json.writeValueAsString(result), RecommendationResult.class);

        assertThat(read).isEqualTo(result);
        assertThat(read.status()).isEqualTo(Status.DATA_INSUFFICIENT);
        assertThat(read.uncompared(1L).orElseThrow().directPaymentAllowed()).isTrue();
    }

    private static RecommendationResult result(List<Candidate> candidates, List<ExcludedCard> excluded,
            List<UncomparedCard> uncompared) {
        return new RecommendationResult("rec_000000000001", AT, false, candidates, excluded, uncompared);
    }

    private static Candidate candidate(int rank, CardSummary card, long netBenefit) {
        return new Candidate(rank, card, netBenefit, List.of(), "", null,
                new RuleVersion(card.cardProductId(), 1));
    }

    private static UncomparedCard uncompared(CardSummary card, UncomparedReason reason) {
        return new UncomparedCard(card, reason, null);
    }

    private static CardSummary card(Long id, CardType type) {
        return new CardSummary(id, 100L + id, type, false, null, String.format("%04d", id), "TEST", "상품");
    }

    /** 스냅샷 저장소와 같게 모르는 칸은 무시한다(Spring 기본 ObjectMapper 설정). */
    private static ObjectMapper json() {
        return JsonMapper.builder().findAndAddModules()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    }
}
