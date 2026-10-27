package com.yamu.be.cashflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class CashflowServiceTest {
    @Mock CashflowItemRepository items;
    @Mock CashflowOccurrenceRepository occurrences;
    @Mock CashflowOwnershipLookup ownership;

    CashflowService service;
    LocalDate tomorrow;

    @BeforeEach
    void setUp() {
        service = new CashflowService(items, occurrences, ownership);
        tomorrow = LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(1);
    }

    @Test
    void 등록시_사용자_소유_계좌를_확인하고_첫_발생건을_함께_저장한다() {
        when(ownership.hasConnectedAccount(7L, 11L)).thenReturn(true);
        when(items.save(any())).thenAnswer(invocation -> {
            CashflowItem item = invocation.getArgument(0);
            ReflectionTestUtils.setField(item, "id", 31L);
            return item;
        });

        CashflowService.Item result = service.create(7L, command(10000L, 11L, null));

        assertThat(result.id()).isEqualTo(31L);
        assertThat(result.accountId()).isEqualTo(11L);
        assertThat(result.merchantCategoryCode()).isEqualTo("UTILITIES");
        assertThat(result.status()).isEqualTo("CONFIRMED");
        ArgumentCaptor<CashflowOccurrence> captured = ArgumentCaptor.forClass(CashflowOccurrence.class);
        verify(occurrences).save(captured.capture());
        assertThat(captured.getValue().getCashflowItemId()).isEqualTo(31L);
        assertThat(captured.getValue().getUserId()).isEqualTo(7L);
        assertThat(captured.getValue().getOccurrenceDate()).isEqualTo(tomorrow);
        assertThat(captured.getValue().getAmount()).isEqualTo(10000L);
        assertThat(captured.getValue().getMerchantCategoryCode()).isEqualTo("UTILITIES");
    }

    @Test
    void 다른_사용자의_계좌나_카드로는_등록할_수_없다() {
        assertThatThrownBy(() -> service.create(7L, command(10000L, 11L, null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
        assertThatThrownBy(() -> service.create(7L, command(10000L, null, 12L)))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
        verify(items, never()).save(any());
    }

    @Test
    void 잘못된_금액과_지난_날짜는_저장하지_않는다() {
        assertThatThrownBy(() -> service.create(7L, command(0L, null, null)))
                .isInstanceOf(BusinessException.class);
        CashflowService.CreateCommand past = new CashflowService.CreateCommand("월세",
                CashflowItem.Direction.EXPENSE, 10000L, tomorrow.minusDays(2),
                CashflowItem.RecurrenceType.NONE, null, null, null, null, null);
        assertThatThrownBy(() -> service.create(7L, past))
                .isInstanceOf(BusinessException.class);
        verify(items, never()).save(any());
    }

    @Test
    void 종료일이_시작일보다_빠르거나_반복하지_않는데_종료일이_있으면_거절한다() {
        CashflowService.CreateCommand wrongEnd = new CashflowService.CreateCommand("월세",
                CashflowItem.Direction.EXPENSE, 10000L, tomorrow,
                CashflowItem.RecurrenceType.MONTHLY, tomorrow.minusDays(1),
                null, null, null, null);
        CashflowService.CreateCommand unusedEnd = new CashflowService.CreateCommand("월세",
                CashflowItem.Direction.EXPENSE, 10000L, tomorrow,
                CashflowItem.RecurrenceType.NONE, tomorrow.plusMonths(1),
                null, null, null, null);

        assertThatThrownBy(() -> service.create(7L, wrongEnd))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.create(7L, unusedEnd))
                .isInstanceOf(BusinessException.class);
        verify(items, never()).save(any());
    }

    @Test
    void 카드_수입은_DB_제약보다_먼저_거절한다() {
        CashflowService.CreateCommand incomeWithCard = new CashflowService.CreateCommand("급여",
                CashflowItem.Direction.INCOME, 10000L, tomorrow,
                CashflowItem.RecurrenceType.NONE, null, null, 12L, null, null);

        assertThatThrownBy(() -> service.create(7L, incomeWithCard))
                .isInstanceOf(BusinessException.class);
        verify(items, never()).save(any());
    }

    @Test
    void 목록은_인증된_사용자와_조회_기간으로_제한한다() {
        when(items.findRulesForUser(7L, tomorrow, tomorrow.plusDays(30),
                CashflowItem.RecurrenceType.NONE, CashflowItem.Direction.EXPENSE))
                .thenReturn(List.of(new CashflowItem(7L, 11L, null, "월세",
                        CashflowItem.Direction.EXPENSE, 10000L, tomorrow,
                        CashflowItem.RecurrenceType.NONE, null, null, null)));

        List<CashflowService.Item> result = service.listRules(7L, tomorrow,
                tomorrow.plusDays(30), CashflowItem.Direction.EXPENSE);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).title()).isEqualTo("월세");
        verify(items).findRulesForUser(7L, tomorrow, tomorrow.plusDays(30),
                CashflowItem.RecurrenceType.NONE, CashflowItem.Direction.EXPENSE);
    }

    @Test
    void 인증된_사용자_식별자가_없으면_조회하지_않는다() {
        assertThatThrownBy(() -> service.listRules(null, tomorrow, tomorrow, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo(ErrorCode.UNAUTHORIZED));
        verify(items, never()).findRulesForUser(any(), any(), any(), any(), any());
    }

    private CashflowService.CreateCommand command(Long amount, Long accountId, Long cardId) {
        return new CashflowService.CreateCommand("월세", CashflowItem.Direction.EXPENSE,
                amount, tomorrow, CashflowItem.RecurrenceType.NONE, null,
                accountId, cardId, "UTILITIES", null);
    }
}
