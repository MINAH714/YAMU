package com.yamu.be.performance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 실적 부족 알림의 카드 고르기(Task 9.3.3). 카드·실적·혜택 요약은 대역이고 판정은 373의 {@link PerformanceService#progress}다. */
class PerformanceShortfallCheckTest {

    static final long USER = 7L;
    /** 11월 25일: 30일인 달이라 5일 남음. 추세 = 실적 × 30 ÷ 25. */
    static final LocalDate TODAY = LocalDate.of(2026, 11, 25);
    static final YearMonth MONTH = YearMonth.of(2026, 11);
    static final long TIERED = 100L;
    static final long PLAIN = 200L;

    UserCardRepository cards = mock(UserCardRepository.class);
    CardProductRepository products = mock(CardProductRepository.class);
    CardActivityReader activity = mock(CardActivityReader.class);
    CardBenefitSummaryReader summaries = mock(CardBenefitSummaryReader.class);
    PerformanceReader reader = mock(PerformanceReader.class);

    PerformanceShortfallCheck check;
    List<UserCard> owned = new ArrayList<>();
    Map<Long, Long> recognized = new HashMap<>();
    Set<Long> provisional = new HashSet<>();

    @BeforeEach
    void setUp() {
        check = new PerformanceShortfallCheck(cards, products, activity, summaries, reader);
        when(cards.findOrdered(eq(USER), any())).thenReturn(owned);
        List<CardProduct> both = List.of(product(TIERED, "노리2"), product(PLAIN, "모두 1%"));
        when(products.findAllById(any())).thenReturn(both);
        when(activity.recognizedAmounts(anyCollection(), eq(MONTH))).thenReturn(recognized);
        when(reader.cardsWithUnconfirmed(eq(USER), anyCollection(), eq(MONTH))).thenReturn(provisional);
        // 기준 30만·50만 상품, 실적 조건이 없는 상품
        when(summaries.summaries(anyCollection(), any())).thenReturn(Map.of(
                TIERED, new CardBenefitSummaryReader.Summary(List.of(), List.of(300_000L, 500_000L), List.of(), 0L),
                PLAIN, new CardBenefitSummaryReader.Summary(List.of(), List.of(), List.of(), 0L)));
    }

    @Test
    void 첫_구간을_못_채울_것_같은_카드를_고른다() {
        card(1L, TIERED, "생활카드", 100_000L);   // 추세 12만 < 30만

        List<PerformanceShortfallCheck.Shortfall> found = check.shortfalls(USER, TODAY);

        assertThat(found).singleElement().satisfies(s -> {
            assertThat(s.userCardId()).isEqualTo(1L);
            assertThat(s.cardName()).isEqualTo("생활카드");
            assertThat(s.target()).isEqualTo(300_000L);
            assertThat(s.targetLabel()).isEqualTo("30만원 이상");
            assertThat(s.recognized()).isEqualTo(100_000L);
            assertThat(s.remaining()).isEqualTo(200_000L);
            assertThat(s.daysLeft()).isEqualTo(5);
            assertThat(s.provisional()).isFalse();
        });
    }

    @Test
    void 별칭이_없으면_상품명이고_실적이_0원이어도_고른다() {
        card(1L, TIERED, null, 0L);

        assertThat(check.shortfalls(USER, TODAY)).singleElement()
                .satisfies(s -> assertThat(s.cardName()).isEqualTo("노리2"));
    }

    @Test
    void 첫_구간을_넘었으면_다음_구간_기준으로_AT_RISK여도_고르지_않는다() {
        card(1L, TIERED, null, 320_000L);   // 목표 50만, 추세 38.4만 < 50만 → AT_RISK지만 첫 구간은 채움

        assertThat(PerformanceService.progress(summary(), 320_000L, MONTH, TODAY).status())
                .isEqualTo(PerformanceStatus.AT_RISK);
        assertThat(check.shortfalls(USER, TODAY)).isEmpty();
    }

    @Test
    void 추세로_첫_구간을_채우거나_모두_채웠거나_실적_조건이나_실적_행이_없으면_고르지_않는다() {
        card(1L, TIERED, null, 260_000L);   // 추세 31.2만 ≥ 30만 → ON_TRACK
        card(2L, TIERED, null, 600_000L);   // ACHIEVED
        card(3L, PLAIN, null, 0L);          // NO_TARGET
        card(4L, TIERED, null, null);       // 실적 행 없음 → INSUFFICIENT_DATA

        assertThat(check.shortfalls(USER, TODAY)).isEmpty();
    }

    @Test
    void 이번_달_첫_5일은_추세를_보지_않아_고르지_않는다() {
        card(1L, TIERED, null, 0L);

        assertThat(check.shortfalls(USER, LocalDate.of(2026, 11, 5))).isEmpty();
        assertThat(check.shortfalls(USER, LocalDate.of(2026, 11, 6))).hasSize(1);
    }

    @Test
    void 인정액_확인_중인_결제가_있는_카드는_표시해서_돌려준다() {
        card(1L, TIERED, null, 100_000L);
        card(2L, TIERED, null, 100_000L);
        provisional.add(2L);

        assertThat(check.shortfalls(USER, TODAY)).extracting(PerformanceShortfallCheck.Shortfall::provisional)
                .containsExactly(false, true);
    }

    @Test
    void 여러_장이면_카드_목록_순서대로_한_장씩이고_말일은_남은_날이_0이다() {
        card(2L, TIERED, "둘", 10_000L);
        card(1L, TIERED, "하나", 10_000L);

        assertThat(check.shortfalls(USER, LocalDate.of(2026, 11, 30)))
                .extracting(PerformanceShortfallCheck.Shortfall::userCardId, PerformanceShortfallCheck.Shortfall::daysLeft)
                .containsExactly(tuple(2L, 0), tuple(1L, 0));
    }

    @Test
    void ACTIVE_카드만_읽고_카드가_없으면_다른_것을_읽지_않는다() {
        assertThat(check.shortfalls(USER, TODAY)).isEmpty();

        verify(cards).findOrdered(USER, EnumSet.of(CardRegistrationStatus.ACTIVE));
        verifyNoInteractions(activity, summaries, reader, products);
    }

    private void card(long id, long productId, String alias, Long amount) {
        UserCard card = mock(UserCard.class);
        when(card.getId()).thenReturn(id);
        when(card.getCardProductId()).thenReturn(productId);
        when(card.getCardAlias()).thenReturn(alias);
        owned.add(card);
        if (amount != null) {
            recognized.put(id, amount);
        }
    }

    private static CardProduct product(long id, String name) {
        CardProduct product = mock(CardProduct.class);
        when(product.getId()).thenReturn(id);
        when(product.getProductName()).thenReturn(name);
        return product;
    }

    private static CardBenefitSummaryReader.Summary summary() {
        return new CardBenefitSummaryReader.Summary(List.of(), List.of(300_000L, 500_000L), List.of(), 0L);
    }
}
