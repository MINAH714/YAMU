package com.yamu.be.cashflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yamu.be.global.common.ApiResponse.FieldErrorResponse;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import java.time.DayOfWeek;
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
    LocalDate today;
    LocalDate tomorrow;

    @BeforeEach
    void setUp() {
        service = new CashflowService(items, occurrences, ownership);
        today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        tomorrow = today.plusDays(1);
    }

    @Test
    void 등록시_사용자_소유_계좌를_확인하고_첫_발생건을_함께_저장한다() {
        when(ownership.hasConnectedAccount(7L, 11L)).thenReturn(true);
        savedWithId(31L);

        CashflowService.Item result = service.create(7L, command(10000L, 11L, null));

        assertThat(result.id()).isEqualTo(31L);
        assertThat(result.accountId()).isEqualTo(11L);
        assertThat(result.merchantCategoryCode()).isEqualTo("UTILITIES");
        assertThat(result.status()).isEqualTo(CashflowItem.Status.CONFIRMED);
        ArgumentCaptor<CashflowOccurrence> captured = ArgumentCaptor.forClass(CashflowOccurrence.class);
        verify(occurrences).save(captured.capture());
        assertThat(captured.getValue().getCashflowItemId()).isEqualTo(31L);
        assertThat(captured.getValue().getUserId()).isEqualTo(7L);
        assertThat(captured.getValue().getOccurrenceDate()).isEqualTo(tomorrow);
        assertThat(captured.getValue().getAmount()).isEqualTo(10000L);
        assertThat(captured.getValue().getMerchantCategoryCode()).isEqualTo("UTILITIES");
    }

    @Test
    void 카드_지출은_계좌_없이_사용자_소유_카드로_등록한다() {
        when(ownership.hasRegisteredCard(7L, 12L)).thenReturn(true);
        savedWithId(32L);

        CashflowService.Item result = service.create(7L, command(10000L, null, 12L));

        assertThat(result.userCardId()).isEqualTo(12L);
        assertThat(result.accountId()).isNull();
        verify(ownership, never()).hasConnectedAccount(any(), any());
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
        assertInvalid(command(0L, 11L, null), "amount");
        assertInvalid(new CashflowService.CreateCommand("월세", CashflowItem.Direction.EXPENSE, 10000L,
                today.minusDays(1), CashflowItem.RecurrenceType.NONE, null, null, null,
                11L, null, null, null), "date");
        verify(items, never()).save(any());
    }

    @Test
    void 오늘_날짜는_첫_발생일로_허용한다() {
        when(ownership.hasConnectedAccount(7L, 11L)).thenReturn(true);
        savedWithId(33L);

        CashflowService.Item result = service.create(7L, new CashflowService.CreateCommand("월세",
                CashflowItem.Direction.EXPENSE, 10000L, today, CashflowItem.RecurrenceType.NONE,
                null, null, null, 11L, null, null, null));

        assertThat(result.scheduledDate()).isEqualTo(today);
    }

    @Test
    void 종료일이_시작일보다_빠르거나_반복하지_않는데_종료일이_있으면_거절한다() {
        assertInvalid(new CashflowService.CreateCommand("월세", CashflowItem.Direction.EXPENSE, 10000L,
                tomorrow, CashflowItem.RecurrenceType.MONTHLY, null, null, tomorrow.minusDays(1),
                11L, null, null, null), "recurrence.endDate");
        assertInvalid(new CashflowService.CreateCommand("월세", CashflowItem.Direction.EXPENSE, 10000L,
                tomorrow, CashflowItem.RecurrenceType.NONE, null, null, tomorrow.plusMonths(1),
                11L, null, null, null), "recurrence.endDate");
        verify(items, never()).save(any());
    }

    @Test
    void 반복일과_요일은_첫_발생일과_일치해야_한다() {
        assertInvalid(new CashflowService.CreateCommand("월세", CashflowItem.Direction.EXPENSE, 10000L,
                tomorrow, CashflowItem.RecurrenceType.MONTHLY, tomorrow.plusDays(1).getDayOfMonth(), null,
                null, 11L, null, null, null), "recurrence.dayOfMonth");
        DayOfWeek otherDay = tomorrow.getDayOfWeek().plus(1);
        assertInvalid(new CashflowService.CreateCommand("헬스장", CashflowItem.Direction.EXPENSE, 10000L,
                tomorrow, CashflowItem.RecurrenceType.WEEKLY, null, otherDay,
                null, 11L, null, null, null), "recurrence.dayOfWeek");
        verify(items, never()).save(any());
    }

    @Test
    void 카드_수입은_DB_제약보다_먼저_거절한다() {
        assertInvalid(new CashflowService.CreateCommand("급여", CashflowItem.Direction.INCOME, 10000L,
                tomorrow, CashflowItem.RecurrenceType.NONE, null, null, null,
                null, 12L, null, null), "userCardId");
        verify(items, never()).save(any());
    }

    @Test
    void 계좌와_카드는_정확히_하나만_지정한다() {
        assertInvalid(command(10000L, null, null), "accountId");
        assertInvalid(command(10000L, 11L, 12L), "userCardId");
        verify(ownership, never()).hasConnectedAccount(any(), any());
        verify(items, never()).save(any());
    }

    @Test
    void 이름은_앞뒤_공백을_뺀_길이로_검사하고_뺀_값으로_저장한다() {
        when(ownership.hasConnectedAccount(7L, 11L)).thenReturn(true);
        savedWithId(34L);
        String thirtyChars = "가".repeat(30);

        CashflowService.Item result = service.create(7L, new CashflowService.CreateCommand(
                "  " + thirtyChars + "  ", CashflowItem.Direction.EXPENSE, 10000L, tomorrow,
                CashflowItem.RecurrenceType.NONE, null, null, null, 11L, null, null, null));

        assertThat(result.title()).isEqualTo(thirtyChars);
        assertInvalid(new CashflowService.CreateCommand("   ", CashflowItem.Direction.EXPENSE, 10000L,
                tomorrow, CashflowItem.RecurrenceType.NONE, null, null, null,
                11L, null, null, null), "title");
    }

    @Test
    void 목록은_인증된_사용자와_조회_기간과_상태로_제한한다() {
        when(items.findRulesForUser(7L, tomorrow, tomorrow.plusDays(30), CashflowItem.ItemKind.SCHEDULED,
                CashflowItem.RecurrenceType.NONE, CashflowItem.Direction.EXPENSE, CashflowItem.Status.CONFIRMED))
                .thenReturn(List.of(new CashflowItem(7L, 11L, null, "월세",
                        CashflowItem.Direction.EXPENSE, 10000L, tomorrow,
                        CashflowItem.RecurrenceType.NONE, null, null, null)));

        List<CashflowService.Item> result = service.listRules(7L, tomorrow,
                tomorrow.plusDays(30), CashflowItem.Direction.EXPENSE, null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).title()).isEqualTo("월세");
    }

    @Test
    void 목록_기간을_생략하면_오늘부터_30일이다() {
        service.listRules(7L, null, null, null, null);

        verify(items).findRulesForUser(7L, today, today.plusDays(29), CashflowItem.ItemKind.SCHEDULED,
                CashflowItem.RecurrenceType.NONE, null, CashflowItem.Status.CONFIRMED);
    }

    @Test
    void 목록의_종료일이_시작일보다_빠르면_거절한다() {
        assertThatThrownBy(() -> service.listRules(7L, tomorrow, today, null, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(fieldOf((BusinessException) error)).isEqualTo("to"));
        verify(items, never()).findRulesForUser(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void 인증된_사용자_식별자가_없으면_조회하지_않는다() {
        assertThatThrownBy(() -> service.listRules(null, tomorrow, tomorrow, null, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo(ErrorCode.UNAUTHORIZED));
        verify(items, never()).findRulesForUser(any(), any(), any(), any(), any(), any(), any());
    }

    private void savedWithId(Long id) {
        when(items.save(any())).thenAnswer(invocation -> {
            CashflowItem item = invocation.getArgument(0);
            ReflectionTestUtils.setField(item, "id", id);
            return item;
        });
    }

    private void assertInvalid(CashflowService.CreateCommand command, String field) {
        assertThatThrownBy(() -> service.create(7L, command))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> {
                    BusinessException business = (BusinessException) error;
                    assertThat(business.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
                    assertThat(fieldOf(business)).isEqualTo(field);
                });
    }

    @SuppressWarnings("unchecked")
    private static String fieldOf(BusinessException error) {
        return ((List<FieldErrorResponse>) error.getDetails().get("fieldErrors")).get(0).field();
    }

    private CashflowService.CreateCommand command(Long amount, Long accountId, Long cardId) {
        return new CashflowService.CreateCommand("월세", CashflowItem.Direction.EXPENSE,
                amount, tomorrow, CashflowItem.RecurrenceType.NONE, null, null, null,
                accountId, cardId, "UTILITIES", null);
    }
}
