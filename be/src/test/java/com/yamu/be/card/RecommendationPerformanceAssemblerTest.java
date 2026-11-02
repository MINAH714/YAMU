package com.yamu.be.card;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.yamu.be.card.RecommendationPerformanceAssembler.Recognition;
import com.yamu.be.recommendation.RecommendationInput.PerformanceProgress;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RecommendationPerformanceAssemblerTest {
    @Mock CardActivityReader reader;
    RecommendationPerformanceAssembler assembler;

    @BeforeEach
    void setUp() {
        assembler = new RecommendationPerformanceAssembler(reader);
    }

    @Test
    void looksUpConfirmedRecognitionMonthEvenWhenItDiffersFromCurrentServerMonth() {
        YearMonth current = YearMonth.now(ZoneId.of("Asia/Seoul"));
        YearMonth recognized = current.minusMonths(2);
        when(reader.recognizedAmounts(List.of(1L), recognized)).thenReturn(Map.of(1L, 290_000L));

        PerformanceProgress progress = assembler.assemble(List.of(new Recognition(1, recognized, 300_000L, 10_000L))).get(1L);

        assertThat(progress.period()).isEqualTo(recognized).isNotEqualTo(current);
        assertThat(progress.beforeAmount()).isEqualTo(290_000L);
        assertThat(progress.newlyMeetsMinimum()).isTrue();
        verify(reader).recognizedAmounts(List.of(1L), recognized);
        verifyNoMoreInteractions(reader);
    }

    @Test
    void groupsCardsByRecognitionMonthAndDoesNotMixRowsOfDifferentMonths() {
        YearMonth september = YearMonth.of(2026, 9);
        YearMonth october = YearMonth.of(2026, 10);
        when(reader.recognizedAmounts(List.of(1L), september)).thenReturn(Map.of(1L, 280_000L, 2L, 999_999L));
        when(reader.recognizedAmounts(List.of(2L), october)).thenReturn(Map.of(1L, 999_999L, 2L, 290_000L));

        var result = assembler.assemble(List.of(new Recognition(1, september, 300_000L, 10_000L),
                new Recognition(2, october, 300_000L, 10_000L)));

        assertThat(result.get(1L)).isEqualTo(new PerformanceProgress(september, 280_000L, 300_000L, 10_000L));
        assertThat(result.get(1L).newlyMeetsMinimum()).isFalse();
        assertThat(result.get(2L)).isEqualTo(new PerformanceProgress(october, 290_000L, 300_000L, 10_000L));
        assertThat(result.get(2L).newlyMeetsMinimum()).isTrue();
        verify(reader).recognizedAmounts(List.of(1L), september);
        verify(reader).recognizedAmounts(List.of(2L), october);
        verifyNoMoreInteractions(reader);
    }

    @Test
    void readsSamePeriodOnceAndPreservesConfirmedZeroVersusMissingRow() {
        YearMonth month = YearMonth.of(2026, 10);
        when(reader.recognizedAmounts(List.of(1L, 2L), month)).thenReturn(Map.of(1L, 0L));
        var result = assembler.assemble(List.of(new Recognition(1, month, 300_000L, 300_000L),
                new Recognition(2, month, 300_000L, 300_000L)));
        assertThat(result.get(1L).beforeAmount()).isZero();
        assertThat(result.get(1L).isKnown()).isTrue();
        assertThat(result.get(1L).newlyMeetsMinimum()).isTrue();
        assertThat(result.get(2L).beforeAmount()).isNull();
        assertThat(result.get(2L).isKnown()).isFalse();
        verify(reader).recognizedAmounts(List.of(1L, 2L), month);
        verifyNoMoreInteractions(reader);
    }

    @Test
    void unknownPolicyInputsDoNotQueryCurrentMonthOrInventMissingValues() {
        YearMonth month = YearMonth.of(2026, 10);
        var result = assembler.assemble(List.of(new Recognition(1, null, 300_000L, 10_000L),
                new Recognition(2, month, null, 10_000L), new Recognition(3, month, 300_000L, null)));
        assertThat(result.values()).allMatch(progress -> !progress.isKnown());
        assertThat(result.get(1L).period()).isNull();
        assertThat(result.get(2L).minimumAmount()).isNull();
        assertThat(result.get(3L).recognizedAmount()).isNull();
        assertThat(result.values()).allMatch(progress -> progress.beforeAmount() == null);
        verifyNoInteractions(reader);
    }

    @Test
    void rejectsAmbiguousDuplicateCardInsteadOfOverwritingItsMonth() {
        assertThatThrownBy(() -> assembler.assemble(List.of(
                new Recognition(1, YearMonth.of(2026, 9), 300_000L, 10_000L),
                new Recognition(1, YearMonth.of(2026, 10), 300_000L, 10_000L))))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(reader);
    }

    @Test
    void rejectsNegativeInputsAndNegativeAggregate() {
        YearMonth month = YearMonth.of(2026, 10);
        assertThatThrownBy(() -> new Recognition(1, month, -1L, 0L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Recognition(1, month, 0L, -1L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Recognition(0, month, 0L, 0L)).isInstanceOf(IllegalArgumentException.class);
        when(reader.recognizedAmounts(List.of(1L), month)).thenReturn(Map.of(1L, -1L));
        assertThatThrownBy(() -> assembler.assemble(List.of(new Recognition(1, month, 300_000L, 10_000L))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void lookupFailureIsNotConvertedToZeroOrUnknownData() {
        YearMonth month = YearMonth.of(2026, 10);
        var failure = new IllegalStateException("조회 실패");
        when(reader.recognizedAmounts(List.of(1L), month)).thenThrow(failure);
        assertThatThrownBy(() -> assembler.assemble(List.of(new Recognition(1, month, 300_000L, 10_000L))))
                .isSameAs(failure);
        verify(reader).recognizedAmounts(List.of(1L), month);
        verifyNoMoreInteractions(reader);
    }

    @Test
    void emptyInputHasNoLookupAndResultCannotBeModified() {
        assertThat(assembler.assemble(List.of())).isEmpty();
        var result = assembler.assemble(List.of(new Recognition(1, null, null, null)));
        assertThatThrownBy(result::clear).isInstanceOf(UnsupportedOperationException.class);
        verifyNoInteractions(reader);
    }
}
