package com.yamu.be.cashflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yamu.be.account.AccountQueryService;
import com.yamu.be.account.LinkedAccountRef;
import com.yamu.be.card.CardQueryService;
import com.yamu.be.card.UserCardRef;
import com.yamu.be.card.domain.CardIssuerStatus;
import com.yamu.be.card.domain.CardRegistrationStatus;
import com.yamu.be.global.common.ApiResponse.FieldErrorResponse;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class CashflowServiceTest {
    @Mock CashflowItemRepository items;
    @Mock CashflowOccurrenceRepository occurrences;
    @Mock CashflowCreateRequestRepository createRequests;
    @Mock AccountQueryService accounts;
    @Mock CardQueryService cards;

    CashflowService service;
    LocalDate today;
    LocalDate tomorrow;

    @BeforeEach
    void setUp() {
        service = new CashflowService(items, occurrences, createRequests, accounts, cards, Clock.systemUTC());
        today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        tomorrow = today.plusDays(1);
    }

    @Test
    void 등록시_사용자_소유_계좌를_확인하고_첫_발생건을_함께_저장한다() {
        when(accounts.requireConnectedAccount(7L, 11L)).thenReturn(account(11L));
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
        when(cards.requireRegisteredCard(7L, 12L)).thenReturn(card(12L, CardIssuerStatus.NORMAL));
        savedWithId(32L);

        CashflowService.Item result = service.create(7L, command(10000L, null, 12L));

        assertThat(result.userCardId()).isEqualTo(12L);
        assertThat(result.accountId()).isNull();
        verify(accounts, never()).requireConnectedAccount(any(), any());
    }

    @Test
    void 다른_사용자의_계좌나_카드로는_등록할_수_없다() {
        // BE 1 조회가 남의 것·없는 것을 404로 던지면 그대로 전달하고 저장하지 않는다.
        when(accounts.requireConnectedAccount(7L, 11L)).thenThrow(new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        when(cards.requireRegisteredCard(7L, 12L)).thenThrow(new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
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
    void 카드사_정지_만료_카드는_결제_카드로_지정할_수_없다() {
        when(cards.requireRegisteredCard(7L, 12L)).thenReturn(card(12L, CardIssuerStatus.SUSPENDED));
        when(cards.requireRegisteredCard(7L, 13L)).thenReturn(card(13L, CardIssuerStatus.EXPIRED));

        assertInvalid(command(10000L, null, 12L), "userCardId");
        assertInvalid(command(10000L, null, 13L), "userCardId");
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
    void 금액은_1000억_원까지만_받는다() {
        // 잔고예보가 여러 항목을 더할 때 넘쳐 500이 나지 않게 막는다.
        when(accounts.requireConnectedAccount(7L, 11L)).thenReturn(account(11L));
        savedWithId(34L);

        assertThat(service.create(7L, command(CashflowService.MAX_AMOUNT, 11L, null)).amount())
                .isEqualTo(100_000_000_000L);
        assertInvalid(command(CashflowService.MAX_AMOUNT + 1, 11L, null), "amount");
    }

    @Test
    void 오늘_날짜는_첫_발생일로_허용한다() {
        when(accounts.requireConnectedAccount(7L, 11L)).thenReturn(account(11L));
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
        verify(accounts, never()).requireConnectedAccount(any(), any());
        verify(items, never()).save(any());
    }

    @Test
    void 이름은_유니코드_공백까지_앞뒤를_뺀_길이로_검사하고_뺀_값으로_저장한다() {
        when(accounts.requireConnectedAccount(7L, 11L)).thenReturn(account(11L));
        savedWithId(34L);
        String thirtyChars = "가".repeat(30);

        // trim()은 U+3000(전각 공백)·U+2003(em 공백)을 지우지 못한다.
        CashflowService.Item result = service.create(7L, new CashflowService.CreateCommand(
                " 　" + thirtyChars + "  ", CashflowItem.Direction.EXPENSE, 10000L, tomorrow,
                CashflowItem.RecurrenceType.NONE, null, null, null, 11L, null, null, null));

        assertThat(result.title()).isEqualTo(thirtyChars);
        assertInvalid(new CashflowService.CreateCommand("　  ", CashflowItem.Direction.EXPENSE, 10000L,
                tomorrow, CashflowItem.RecurrenceType.NONE, null, null, null,
                11L, null, null, null), "title");
    }

    @Test
    void 빈_업종_코드와_메모는_NULL로_저장하고_값은_앞뒤_공백을_뺀다() {
        when(accounts.requireConnectedAccount(7L, 11L)).thenReturn(account(11L));
        savedWithId(35L);

        service.create(7L, new CashflowService.CreateCommand("월세", CashflowItem.Direction.EXPENSE, 10000L,
                tomorrow, CashflowItem.RecurrenceType.NONE, null, null, null, 11L, null, "  ", "　"));
        service.create(7L, new CashflowService.CreateCommand("월세", CashflowItem.Direction.EXPENSE, 10000L,
                tomorrow, CashflowItem.RecurrenceType.NONE, null, null, null, 11L, null, " CAFE ", " 메모 "));

        ArgumentCaptor<CashflowItem> saved = ArgumentCaptor.forClass(CashflowItem.class);
        verify(items, times(2)).save(saved.capture());
        assertThat(saved.getAllValues().get(0).getMerchantCategoryCode()).isNull();
        assertThat(saved.getAllValues().get(0).getMemo()).isNull();
        assertThat(saved.getAllValues().get(1).getMerchantCategoryCode()).isEqualTo("CAFE");
        assertThat(saved.getAllValues().get(1).getMemo()).isEqualTo("메모");
    }

    @Test
    void 목록은_인증된_사용자와_조회_기간과_상태로_제한한다() {
        when(items.findRulesForUser(7L, tomorrow, tomorrow.plusDays(30), CashflowItem.ItemKind.SCHEDULED,
                CashflowItem.RecurrenceType.NONE, CashflowItem.Direction.EXPENSE, CashflowItem.Status.CONFIRMED))
                .thenReturn(List.of(new CashflowItem(7L, 11L, null, "월세",
                        CashflowItem.Direction.EXPENSE, 10000L, tomorrow,
                        CashflowItem.RecurrenceType.NONE, null, null, null, null)));

        List<CashflowService.Entry> result = service.list(7L, tomorrow,
                tomorrow.plusDays(30), CashflowItem.Direction.EXPENSE, null, true);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).item().title()).isEqualTo("월세");
    }

    @Test
    void 매주_반복은_기간_안의_같은_요일마다_펼친다() {
        CashflowService.Item rule = rule(LocalDate.of(2026, 11, 2), CashflowItem.RecurrenceType.WEEKLY, null);

        assertThat(CashflowService.occurrenceDates(rule, LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 30)))
                .containsExactly(LocalDate.of(2026, 11, 16), LocalDate.of(2026, 11, 23), LocalDate.of(2026, 11, 30));
    }

    @Test
    void 매월_반복은_없는_날짜만_말일로_맞추고_다음_달은_원래_날짜로_돌아온다() {
        CashflowService.Item rule = rule(LocalDate.of(2027, 1, 31), CashflowItem.RecurrenceType.MONTHLY, null);

        assertThat(CashflowService.occurrenceDates(rule, LocalDate.of(2027, 1, 1), LocalDate.of(2027, 5, 31)))
                .containsExactly(LocalDate.of(2027, 1, 31), LocalDate.of(2027, 2, 28), LocalDate.of(2027, 3, 31),
                        LocalDate.of(2027, 4, 30), LocalDate.of(2027, 5, 31));
        // 시작일이 기간보다 앞서도 말일 보정된 회차(2/28)를 빠뜨리지 않는다.
        assertThat(CashflowService.occurrenceDates(rule, LocalDate.of(2027, 2, 28), LocalDate.of(2027, 3, 30)))
                .containsExactly(LocalDate.of(2027, 2, 28));
    }

    @Test
    void 말일에_시작한_매월_반복은_기준일로_이어진다() {
        // 31일 반복을 11/30에서 나눈 규칙. 첫 발생일만 보면 12/30·1/30이 되지만 기준일 31로 이어진다.
        CashflowService.Item rule = new CashflowService.Item(1L, 11L, null, "월세", CashflowItem.Direction.EXPENSE,
                10000L, LocalDate.of(2026, 11, 30), CashflowItem.RecurrenceType.MONTHLY, null, 31, null,
                CashflowItem.Status.CONFIRMED, null);

        assertThat(CashflowService.occurrenceDates(rule, LocalDate.of(2026, 11, 1), LocalDate.of(2027, 4, 30)))
                .containsExactly(LocalDate.of(2026, 11, 30), LocalDate.of(2026, 12, 31), LocalDate.of(2027, 1, 31),
                        LocalDate.of(2027, 2, 28), LocalDate.of(2027, 3, 31), LocalDate.of(2027, 4, 30));
        assertThat(CashflowService.occurrenceDates(rule, LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 31)))
                .containsExactly(LocalDate.of(2027, 1, 31));
    }

    @Test
    void 말일_시작이면_더_큰_반복일을_받아_기준일로_저장한다() {
        when(accounts.requireConnectedAccount(7L, 11L)).thenReturn(account(11L));
        savedWithId(36L);
        // 내일 이후 첫 30일 이하인 달의 말일
        java.time.YearMonth month = java.time.YearMonth.from(tomorrow);
        while (month.lengthOfMonth() == 31 || !month.atEndOfMonth().isAfter(today)) {
            month = month.plusMonths(1);
        }
        LocalDate shortMonthEnd = month.atEndOfMonth();

        CashflowService.Item result = service.create(7L, new CashflowService.CreateCommand("월세",
                CashflowItem.Direction.EXPENSE, 10000L, shortMonthEnd, CashflowItem.RecurrenceType.MONTHLY, 31, null,
                null, 11L, null, null, null));

        assertThat(result.recurrenceDayOfMonth()).isEqualTo(31);
        assertThat(CashflowItemResponse.from(result).recurrence().dayOfMonth()).isEqualTo(31);
        assertInvalid(new CashflowService.CreateCommand("월세", CashflowItem.Direction.EXPENSE, 10000L,
                shortMonthEnd.minusDays(1), CashflowItem.RecurrenceType.MONTHLY, 31, null,
                null, 11L, null, null, null), "recurrence.dayOfMonth");
    }

    @Test
    void 반복_종료일은_그날까지_포함하고_기간_밖_회차와_일회성은_제외한다() {
        CashflowService.Item ending = rule(LocalDate.of(2026, 11, 5), CashflowItem.RecurrenceType.MONTHLY,
                LocalDate.of(2027, 1, 5));
        assertThat(CashflowService.occurrenceDates(ending, LocalDate.of(2026, 11, 1), LocalDate.of(2027, 3, 31)))
                .containsExactly(LocalDate.of(2026, 11, 5), LocalDate.of(2026, 12, 5), LocalDate.of(2027, 1, 5));
        // 기간과는 겹치지만 기간 안에 발생일이 없는 규칙
        assertThat(CashflowService.occurrenceDates(ending, LocalDate.of(2026, 11, 6), LocalDate.of(2026, 12, 4)))
                .isEmpty();

        CashflowService.Item once = rule(LocalDate.of(2026, 11, 5), CashflowItem.RecurrenceType.NONE, null);
        assertThat(CashflowService.occurrenceDates(once, LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 5)))
                .containsExactly(LocalDate.of(2026, 11, 5));
        assertThat(CashflowService.occurrenceDates(once, LocalDate.of(2026, 11, 6), LocalDate.of(2026, 11, 30)))
                .isEmpty();
    }

    @Test
    void 펼친_목록은_발생일_순이고_expand_false면_규칙을_첫_발생일로_한_번만_돌려준다() {
        LocalDate from = today;
        LocalDate to = today.plusDays(20);
        CashflowItem weekly = new CashflowItem(7L, 11L, null, "헬스장", CashflowItem.Direction.EXPENSE, 10000L,
                today, CashflowItem.RecurrenceType.WEEKLY, null, null, null, null);
        CashflowItem once = new CashflowItem(7L, 11L, null, "병원", CashflowItem.Direction.EXPENSE, 30000L,
                today.plusDays(8), CashflowItem.RecurrenceType.NONE, null, null, null, null);
        ReflectionTestUtils.setField(weekly, "id", 41L);
        ReflectionTestUtils.setField(once, "id", 42L);
        when(items.findRulesForUser(7L, from, to, CashflowItem.ItemKind.SCHEDULED, CashflowItem.RecurrenceType.NONE,
                null, CashflowItem.Status.CONFIRMED)).thenReturn(List.of(weekly, once));

        assertThat(service.list(7L, from, to, null, null, true))
                .extracting(entry -> entry.item().id(), CashflowService.Entry::occurrenceDate)
                .containsExactly(tuple(41L, today), tuple(41L, today.plusDays(7)), tuple(42L, today.plusDays(8)),
                        tuple(41L, today.plusDays(14)));
        assertThat(service.list(7L, from, to, null, null, false))
                .extracting(entry -> entry.item().id(), CashflowService.Entry::occurrenceDate)
                .containsExactly(tuple(41L, today), tuple(42L, today.plusDays(8)));
    }

    @Test
    void 이번_회차만_옮긴_회차는_옮긴_날짜로_기간_안팎을_가르고_원래_회차를_식별값으로_돌려준다() {
        LocalDate from = LocalDate.of(2026, 11, 1);
        CashflowItem weekly = saved(41L, new CashflowItem(7L, 11L, null, "헬스장", CashflowItem.Direction.EXPENSE,
                10000L, LocalDate.of(2026, 11, 2), CashflowItem.RecurrenceType.WEEKLY, null, null, null, null));
        CashflowOccurrence moved = occurrence(51L, weekly, LocalDate.of(2026, 11, 9));
        moved.override(LocalDate.of(2026, 11, 11), "헬스장(옮김)", 5000L, 11L, null, null, null);
        rulesBetween(from, LocalDate.of(2026, 11, 30), weekly);
        when(occurrences.findForUserBetween(7L, from, LocalDate.of(2026, 11, 30))).thenReturn(List.of(moved));

        assertThat(service.list(7L, from, LocalDate.of(2026, 11, 30), null, null, true))
                .extracting(CashflowService.Entry::occurrenceDate, CashflowService.Entry::date,
                        entry -> entry.item().amount())
                .containsExactly(tuple(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 2), 10000L),
                        tuple(LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 11), 5000L),
                        tuple(LocalDate.of(2026, 11, 16), LocalDate.of(2026, 11, 16), 10000L),
                        tuple(LocalDate.of(2026, 11, 23), LocalDate.of(2026, 11, 23), 10000L),
                        tuple(LocalDate.of(2026, 11, 30), LocalDate.of(2026, 11, 30), 10000L));

        // 원래 회차(11/9)는 기간 안이지만 옮긴 날짜(11/11)가 기간 밖이면 보이지 않는다.
        rulesBetween(from, LocalDate.of(2026, 11, 10), weekly);
        when(occurrences.findForUserBetween(7L, from, LocalDate.of(2026, 11, 10))).thenReturn(List.of(moved));
        assertThat(service.list(7L, from, LocalDate.of(2026, 11, 10), null, null, true))
                .extracting(CashflowService.Entry::date).containsExactly(LocalDate.of(2026, 11, 2));

        // 원래 회차는 기간 밖이지만 옮긴 날짜가 기간 안이면 보인다.
        LocalDate later = LocalDate.of(2026, 11, 10);
        rulesBetween(later, LocalDate.of(2026, 11, 12), weekly);
        when(occurrences.findForUserBetween(7L, later, LocalDate.of(2026, 11, 12))).thenReturn(List.of(moved));
        assertThat(service.list(7L, later, LocalDate.of(2026, 11, 12), null, null, true))
                .extracting(CashflowService.Entry::occurrenceDate, CashflowService.Entry::date)
                .containsExactly(tuple(LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 11)));
    }

    @Test
    void 제외한_회차는_빠지고_덮어쓰지_않은_회차_행은_규칙_값으로_한_번만_나온다() {
        LocalDate from = LocalDate.of(2026, 11, 1);
        LocalDate to = LocalDate.of(2026, 11, 30);
        CashflowItem weekly = saved(41L, new CashflowItem(7L, 11L, null, "헬스장", CashflowItem.Direction.EXPENSE,
                10000L, LocalDate.of(2026, 11, 2), CashflowItem.RecurrenceType.WEEKLY, null, null, null, null));
        CashflowOccurrence first = occurrence(51L, weekly, LocalDate.of(2026, 11, 2));
        CashflowOccurrence excluded = occurrence(52L, weekly, LocalDate.of(2026, 11, 16));
        excluded.exclude();
        rulesBetween(from, to, weekly);
        when(occurrences.findForUserBetween(7L, from, to)).thenReturn(List.of(first, excluded));

        assertThat(service.list(7L, from, to, null, null, true))
                .extracting(CashflowService.Entry::occurrenceDate)
                .containsExactly(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 9),
                        LocalDate.of(2026, 11, 23), LocalDate.of(2026, 11, 30));
    }

    @Test
    void 결제와_연결된_회차는_덮어쓰지_않았어도_그_행의_값을_쓴다() {
        LocalDate from = LocalDate.of(2026, 11, 1);
        LocalDate to = LocalDate.of(2026, 11, 30);
        CashflowItem monthly = saved(41L, new CashflowItem(7L, 11L, null, "구독", CashflowItem.Direction.EXPENSE,
                12000L, LocalDate.of(2026, 11, 5), CashflowItem.RecurrenceType.MONTHLY, null, null, null, null));
        CashflowOccurrence paid = occurrence(51L, monthly, LocalDate.of(2026, 11, 5));
        // 결제 연결 뒤 규칙 금액이 바뀐 상황: 행은 결제 당시 금액을 갖고 있다.
        ReflectionTestUtils.setField(monthly, "amount", 15000L);
        rulesBetween(from, to, monthly);
        when(occurrences.findForUserBetween(7L, from, to)).thenReturn(List.of(paid));
        when(occurrences.findMatchedIds(List.of(51L))).thenReturn(List.of(51L));

        assertThat(service.list(7L, from, to, null, null, true))
                .extracting(CashflowService.Entry::occurrenceDate, entry -> entry.item().amount())
                .containsExactly(tuple(LocalDate.of(2026, 11, 5), 12000L));
    }

    @Test
    void 결제와_연결된_회차만_paid로_표시해_잔고예보가_예정_차감에서_뺄_수_있게_한다() {
        LocalDate from = LocalDate.of(2026, 11, 1);
        LocalDate to = LocalDate.of(2026, 11, 30);
        CashflowItem weekly = saved(41L, new CashflowItem(7L, 11L, null, "헬스장", CashflowItem.Direction.EXPENSE,
                10000L, LocalDate.of(2026, 11, 2), CashflowItem.RecurrenceType.WEEKLY, null, null, null, null));
        CashflowOccurrence paid = occurrence(51L, weekly, LocalDate.of(2026, 11, 2));
        CashflowOccurrence moved = occurrence(52L, weekly, LocalDate.of(2026, 11, 9));
        moved.override(LocalDate.of(2026, 11, 10), "헬스장", 10000L, 11L, null, null, null);
        CashflowOccurrence canceled = occurrence(53L, weekly, LocalDate.of(2026, 11, 16));
        rulesBetween(from, to, weekly);
        when(occurrences.findForUserBetween(7L, from, to)).thenReturn(List.of(paid, moved, canceled));
        when(occurrences.findMatchedIds(List.of(51L, 52L, 53L))).thenReturn(List.of(51L, 53L));
        // 53은 연결된 결제가 취소됐다(S15P31E103-319).
        when(occurrences.findPaidIds(Set.of(51L, 53L))).thenReturn(List.of(51L));

        // 취소되지 않은 결제와 연결된 회차만 paid다. 이번 회차만 고친 회차, 연결 결제가 취소된 회차,
        // 규칙 값으로 계산한 회차는 아니다.
        assertThat(service.list(7L, from, to, null, null, true))
                .extracting(CashflowService.Entry::occurrenceDate, CashflowService.Entry::paid)
                .containsExactly(tuple(LocalDate.of(2026, 11, 2), true), tuple(LocalDate.of(2026, 11, 9), false),
                        tuple(LocalDate.of(2026, 11, 16), false), tuple(LocalDate.of(2026, 11, 23), false),
                        tuple(LocalDate.of(2026, 11, 30), false));
    }

    @Test
    void 기간_밖_규칙에서_옮겨_들어온_회차도_목록_조건을_걸어_보여_준다() {
        LocalDate from = LocalDate.of(2026, 12, 1);
        LocalDate to = LocalDate.of(2026, 12, 31);
        CashflowItem ended = saved(41L, new CashflowItem(7L, 11L, null, "월세", CashflowItem.Direction.EXPENSE,
                500000L, LocalDate.of(2026, 10, 25), CashflowItem.RecurrenceType.MONTHLY,
                LocalDate.of(2026, 11, 30), null, null, null));
        CashflowOccurrence moved = occurrence(51L, ended, LocalDate.of(2026, 11, 25));
        moved.override(LocalDate.of(2026, 12, 2), "월세", 500000L, 11L, null, null, null);
        rulesBetween(from, to);
        when(occurrences.findForUserBetween(7L, from, to)).thenReturn(List.of(moved));
        when(items.findAllById(Set.of(41L))).thenReturn(List.of(ended));

        assertThat(service.list(7L, from, to, null, null, true))
                .extracting(entry -> entry.item().id(), CashflowService.Entry::date)
                .containsExactly(tuple(41L, LocalDate.of(2026, 12, 2)));
        // 같은 회차도 유형 조건이 맞지 않으면 빠진다.
        when(items.findRulesForUser(7L, from, to, CashflowItem.ItemKind.SCHEDULED, CashflowItem.RecurrenceType.NONE,
                CashflowItem.Direction.INCOME, CashflowItem.Status.CONFIRMED)).thenReturn(List.of());
        assertThat(service.list(7L, from, to, CashflowItem.Direction.INCOME, null, true)).isEmpty();
    }

    /** 한국 시각 2026-11-01 00:30. UTC로는 아직 10/31이라 오늘 계산이 한국 날짜를 쓰는지 드러난다. */
    private static final Clock NOV_1_KST = Clock.fixed(Instant.parse("2026-10-31T15:30:00Z"), ZoneOffset.UTC);
    private static final LocalDate OCT_25 = LocalDate.of(2026, 10, 25);
    private static final LocalDate NOV_1 = LocalDate.of(2026, 11, 1);
    private static final LocalDate NOV_8 = LocalDate.of(2026, 11, 8);

    @Test
    void 이미_옮긴_회차를_다시_고치면_옮긴_날짜와_고친_값_위에_보낸_필드만_바꾼다() {
        CashflowService fixed = fixedService();
        CashflowItem weekly = lockable(weeklyFrom(OCT_25));
        CashflowOccurrence moved = occurrence(51L, weekly, NOV_8);
        moved.override(LocalDate.of(2026, 11, 10), "헬스장(옮김)", 5000L, 11L, null, null, "옮겼음");
        when(occurrences.findByCashflowItemIdAndOccurrenceDate(41L, NOV_8)).thenReturn(Optional.of(moved));

        CashflowService.Entry result = fixed.update(7L, 41L, thisOnly(NOV_8).amount(7000L).build());

        assertThat(moved.getScheduledDate()).isEqualTo(LocalDate.of(2026, 11, 10));
        assertThat(moved.getAmount()).isEqualTo(7000L);
        assertThat(moved.getTitle()).isEqualTo("헬스장(옮김)");
        assertThat(moved.getMemo()).isEqualTo("옮겼음");
        assertThat(result.occurrenceDate()).isEqualTo(NOV_8);
        assertThat(result.date()).isEqualTo(LocalDate.of(2026, 11, 10));
        verify(occurrences, never()).save(any());
    }

    @Test
    void 처음_고치는_회차는_규칙_값_위에_바꾸고_회차_행을_새로_저장한다() {
        CashflowService fixed = fixedService();
        lockable(weeklyFrom(OCT_25));
        when(occurrences.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        CashflowService.Entry result = fixed.update(7L, 41L, thisOnly(NOV_8).date(LocalDate.of(2026, 11, 9)).build());

        ArgumentCaptor<CashflowOccurrence> saved = ArgumentCaptor.forClass(CashflowOccurrence.class);
        verify(occurrences).save(saved.capture());
        assertThat(saved.getValue().getOccurrenceDate()).isEqualTo(NOV_8);
        assertThat(saved.getValue().getScheduledDate()).isEqualTo(LocalDate.of(2026, 11, 9));
        assertThat(saved.getValue().getAmount()).isEqualTo(10000L);
        assertThat(saved.getValue().isOverridden()).isTrue();
        assertThat(result.item().id()).isEqualTo(41L);
    }

    @Test
    void 오늘은_한국_날짜로_계산해_오늘_회차는_고치고_지난_회차는_거절한다() {
        CashflowService fixed = fixedService();
        lockable(weeklyFrom(OCT_25));
        when(occurrences.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        fixed.update(7L, 41L, thisOnly(NOV_1).amount(7000L).build());
        assertUpdateInvalid(fixed, thisOnly(OCT_25).amount(7000L).build(), "occurrenceDate");
        // 회차를 지난 날짜로 옮길 수 없다.
        assertUpdateInvalid(fixed, thisOnly(NOV_8).date(OCT_25).build(), "date");
    }

    @Test
    void 이번_회차_수정은_실제_회차_날짜가_필요하고_반복과_유형은_바꿀_수_없다() {
        CashflowService fixed = fixedService();
        lockable(weeklyFrom(OCT_25));

        assertUpdateInvalid(fixed, thisOnly(null).amount(7000L).build(), "occurrenceDate");
        assertUpdateInvalid(fixed, thisOnly(LocalDate.of(2026, 11, 9)).amount(7000L).build(), "occurrenceDate");
        assertUpdateInvalid(fixed, thisOnly(NOV_8).recurrence(new CashflowService.RecurrenceChange(
                CashflowItem.RecurrenceType.MONTHLY, null, null, null)).build(), "recurrence");
        assertUpdateInvalid(fixed, thisOnly(NOV_8).type(CashflowItem.Direction.INCOME).build(), "type");
        verify(occurrences, never()).save(any());
    }

    @Test
    void 빈_메모는_지우고_계좌나_카드를_보내면_다른_하나를_비운다() {
        CashflowService fixed = fixedService();
        lockable(weeklyFrom(OCT_25));
        when(occurrences.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(cards.requireRegisteredCard(7L, 12L)).thenReturn(card(12L, CardIssuerStatus.NORMAL));

        CashflowService.Entry result = fixed.update(7L, 41L, thisOnly(NOV_8).userCardId(12L).memo(" ").build());

        assertThat(result.item().userCardId()).isEqualTo(12L);
        assertThat(result.item().accountId()).isNull();
        assertThat(result.item().memo()).isNull();
        verify(accounts, never()).requireConnectedAccount(any(), any());
        assertUpdateInvalid(fixed, thisOnly(NOV_8).accountId(11L).userCardId(12L).build(), "userCardId");
        assertUpdateInvalid(fixed, thisOnly(NOV_8).title(" ").build(), "title");
    }

    @Test
    void 수입_회차에는_카드를_지정할_수_없다() {
        CashflowService fixed = fixedService();
        lockable(new CashflowItem(7L, 11L, null, "월급", CashflowItem.Direction.INCOME,
                3000000L, OCT_25, CashflowItem.RecurrenceType.WEEKLY, null, null, null, null));

        assertUpdateInvalid(fixed, thisOnly(NOV_8).userCardId(12L).build(), "userCardId");
        verify(cards, never()).requireRegisteredCard(any(), any());
    }

    @Test
    void 지운_회차와_결제와_연결된_회차는_고칠_수_없다() {
        CashflowService fixed = fixedService();
        CashflowItem weekly = lockable(weeklyFrom(OCT_25));
        CashflowOccurrence excluded = occurrence(51L, weekly, NOV_8);
        excluded.exclude();
        CashflowOccurrence paid = occurrence(52L, weekly, NOV_1);
        when(occurrences.findByCashflowItemIdAndOccurrenceDate(41L, NOV_8)).thenReturn(Optional.of(excluded));
        when(occurrences.findByCashflowItemIdAndOccurrenceDate(41L, NOV_1)).thenReturn(Optional.of(paid));
        when(occurrences.findMatchedIds(List.of(52L))).thenReturn(List.of(52L));

        assertUpdateInvalid(fixed, thisOnly(NOV_8).amount(7000L).build(), "occurrenceDate");
        assertThatThrownBy(() -> fixed.update(7L, 41L, thisOnly(NOV_1).amount(7000L).build()))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo(ErrorCode.PAID_OCCURRENCE_LOCKED));
        assertThatThrownBy(() -> fixed.delete(7L, 41L, CashflowService.DeleteScope.THIS_ONLY, NOV_1))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo(ErrorCode.PAID_OCCURRENCE_LOCKED));
        assertThat(paid.getStatus()).isEqualTo(CashflowOccurrence.Status.CONFIRMED);
        assertThat(paid.getAmount()).isEqualTo(10000L);
    }

    @Test
    void 이번_회차_삭제는_회차_행을_EXCLUDED로_남기고_다시_지워도_그대로다() {
        CashflowService fixed = fixedService();
        lockable(weeklyFrom(OCT_25));
        when(occurrences.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        fixed.delete(7L, 41L, CashflowService.DeleteScope.THIS_ONLY, NOV_8);

        ArgumentCaptor<CashflowOccurrence> saved = ArgumentCaptor.forClass(CashflowOccurrence.class);
        verify(occurrences).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(CashflowOccurrence.Status.EXCLUDED);
        assertThat(saved.getValue().getOccurrenceDate()).isEqualTo(NOV_8);

        when(occurrences.findByCashflowItemIdAndOccurrenceDate(41L, NOV_8)).thenReturn(Optional.of(saved.getValue()));
        fixed.delete(7L, 41L, CashflowService.DeleteScope.THIS_ONLY, NOV_8);
        verify(occurrences, times(1)).save(any());
    }

    @Test
    void 없거나_다른_사용자의_항목은_수정_삭제가_404다() {
        CashflowService fixed = fixedService();
        when(items.findLink(41L, 7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> fixed.update(7L, 41L, thisOnly(NOV_8).amount(7000L).build()))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
        assertThatThrownBy(() -> fixed.delete(7L, 41L, CashflowService.DeleteScope.THIS_ONLY, NOV_8))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
        verify(items, never()).findForUpdate(any(), any());
    }

    @Test
    void 나뉜_규칙은_시리즈의_첫_규칙을_잠근_뒤_이어진_규칙을_읽는다() {
        CashflowService fixed = fixedService();
        CashflowItem head = saved(40L, weeklyFrom(LocalDate.of(2026, 10, 4)));
        CashflowItem selected = saved(41L, weeklyFrom(OCT_25));
        when(items.findLink(41L, 7L)).thenReturn(Optional.of(link(41L, 40L)));
        when(items.findLink(40L, 7L)).thenReturn(Optional.of(link(40L, null)));
        when(items.findForUpdate(40L, 7L)).thenReturn(Optional.of(head));
        when(items.findByPreviousCashflowItemIdAndUserId(40L, 7L)).thenReturn(List.of(selected));
        when(occurrences.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        fixed.update(7L, 41L, thisOnly(NOV_8).amount(7000L).build());

        InOrder order = Mockito.inOrder(items);
        order.verify(items).findForUpdate(40L, 7L);
        order.verify(items).findByPreviousCashflowItemIdAndUserId(40L, 7L);
        verify(items, never()).findForUpdate(41L, 7L);
    }

    @Test
    void 탐지_후보는_확인이나_무시로만_바꿀_수_있고_나머지_전환은_409다() {
        CashflowService fixed = fixedService();
        CashflowItem rule = lockable(weeklyFrom(OCT_25));

        // 같은 상태를 보내면 그대로 받는다.
        fixed.update(7L, 41L, new UpdateBuilder().status(CashflowItem.Status.CONFIRMED).build());
        for (CashflowItem.Status to : List.of(CashflowItem.Status.IGNORED, CashflowItem.Status.CANDIDATE)) {
            assertStatusChangeRejected(fixed, to);
        }

        ReflectionTestUtils.setField(rule, "status", CashflowItem.Status.CANDIDATE);
        fixed.update(7L, 41L, new UpdateBuilder().status(CashflowItem.Status.CONFIRMED).build());
        assertThat(rule.getStatus()).isEqualTo(CashflowItem.Status.CONFIRMED);

        ReflectionTestUtils.setField(rule, "status", CashflowItem.Status.CANDIDATE);
        fixed.update(7L, 41L, new UpdateBuilder().status(CashflowItem.Status.IGNORED).build());
        assertThat(rule.getStatus()).isEqualTo(CashflowItem.Status.IGNORED);
        assertStatusChangeRejected(fixed, CashflowItem.Status.CONFIRMED);

        // 상태만 바꾸면 규칙을 나누거나 회차를 지우지 않는다.
        verify(items, never()).save(any());
        verify(occurrences, never()).deleteFrom(any(), any());
    }

    @Test
    void 이번_회차만_바꿀_때는_상태를_보낼_수_없다() {
        CashflowService fixed = fixedService();
        lockable(weeklyFrom(OCT_25));

        assertUpdateInvalid(fixed, thisOnly(NOV_8).status(CashflowItem.Status.IGNORED).build(), "status");
    }

    @Test
    void 탐지_후보를_지우면_시리즈_전체를_무시로_바꾸고_행은_남긴다() {
        CashflowService fixed = fixedService();
        CashflowItem rule = lockable(weeklyFrom(OCT_25));
        ReflectionTestUtils.setField(rule, "status", CashflowItem.Status.CANDIDATE);

        fixed.delete(7L, 41L, CashflowService.DeleteScope.THIS_ONLY, NOV_8);

        assertThat(rule.getStatus()).isEqualTo(CashflowItem.Status.IGNORED);
        verify(items, never()).delete(any());
        verify(occurrences, never()).save(any());
    }

    @Test
    void 바꿀_값이_없는_수정_요청은_시작한_규칙도_나누지_않는다() {
        CashflowService fixed = fixedService();
        lockable(weeklyFrom(OCT_25));

        CashflowService.Entry result = fixed.update(7L, 41L, new UpdateBuilder().build());

        assertThat(result.item().id()).isEqualTo(41L);
        verify(items, never()).save(any());
        verify(occurrences, never()).deleteFrom(any(), any());
    }

    @Test
    void 목록_기간을_생략하면_오늘부터_30일이다() {
        service.list(7L, null, null, null, null, true);

        verify(items).findRulesForUser(7L, today, today.plusDays(29), CashflowItem.ItemKind.SCHEDULED,
                CashflowItem.RecurrenceType.NONE, null, CashflowItem.Status.CONFIRMED);
    }

    @Test
    void 목록의_종료일이_시작일보다_빠르면_거절한다() {
        assertThatThrownBy(() -> service.list(7L, tomorrow, today, null, null, true))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(fieldOf((BusinessException) error)).isEqualTo("to"));
        verify(items, never()).findRulesForUser(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void 목록_기간은_시작일과_종료일을_포함해_최대_366일이다() {
        service.list(7L, today, today.plusDays(365), null, null, true);
        verify(items).findRulesForUser(7L, today, today.plusDays(365), CashflowItem.ItemKind.SCHEDULED,
                CashflowItem.RecurrenceType.NONE, null, CashflowItem.Status.CONFIRMED);

        assertThatThrownBy(() -> service.list(7L, today, today.plusDays(366), null, null, true))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(fieldOf((BusinessException) error)).isEqualTo("to"));
        assertThatThrownBy(() -> service.list(7L, today, LocalDate.of(9999, 12, 31), null, null, true))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(fieldOf((BusinessException) error)).isEqualTo("to"));
    }

    @Test
    void DB가_담을_수_없는_날짜는_400으로_거절한다() {
        // 9999-12-31에 기본 기간 29일을 더하면 10000년이 되어 DB로 넘어간다.
        assertListInvalid(LocalDate.of(9999, 12, 31), null, "from");
        // 이 시작일에 29일을 더하면 LocalDate 범위를 넘어 DateTimeException(500)이 났다.
        assertListInvalid(LocalDate.MAX, null, "from");
        assertListInvalid(LocalDate.of(999, 12, 31), today, "from");
        assertListInvalid(LocalDate.of(9999, 12, 31), LocalDate.of(10000, 1, 1), "to");
        verify(items, never()).findRulesForUser(any(), any(), any(), any(), any(), any(), any());

        assertInvalid(new CashflowService.CreateCommand("월세", CashflowItem.Direction.EXPENSE, 10000L,
                LocalDate.of(10000, 1, 1), CashflowItem.RecurrenceType.NONE, null, null, null,
                11L, null, null, null), "date");
        assertInvalid(new CashflowService.CreateCommand("월세", CashflowItem.Direction.EXPENSE, 10000L,
                tomorrow, CashflowItem.RecurrenceType.MONTHLY, null, null, LocalDate.of(10000, 1, 1),
                11L, null, null, null), "recurrence.endDate");
        verify(items, never()).save(any());
    }

    @Test
    void 인증된_사용자_식별자가_없으면_조회하지_않는다() {
        assertThatThrownBy(() -> service.list(null, tomorrow, tomorrow, null, null, true))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo(ErrorCode.UNAUTHORIZED));
        verify(items, never()).findRulesForUser(any(), any(), any(), any(), any(), any(), any());
    }

    private CashflowService fixedService() {
        return new CashflowService(items, occurrences, createRequests, accounts, cards, NOV_1_KST);
    }

    private static CashflowItem weeklyFrom(LocalDate first) {
        return new CashflowItem(7L, 11L, null, "헬스장", CashflowItem.Direction.EXPENSE, 10000L,
                first, CashflowItem.RecurrenceType.WEEKLY, null, null, null, null);
    }

    /** 규칙 하나뿐인 시리즈의 41번 규칙으로 잠글 수 있게 한다. */
    private CashflowItem lockable(CashflowItem rule) {
        saved(41L, rule);
        when(items.findLink(41L, 7L)).thenReturn(Optional.of(link(41L, null)));
        when(items.findForUpdate(41L, 7L)).thenReturn(Optional.of(rule));
        return rule;
    }

    private static CashflowItemRepository.Link link(Long id, Long previousId) {
        return new CashflowItemRepository.Link() {
            @Override
            public Long getId() {
                return id;
            }

            @Override
            public Long getPreviousCashflowItemId() {
                return previousId;
            }
        };
    }

    private static UpdateBuilder thisOnly(LocalDate occurrenceDate) {
        return new UpdateBuilder().scope(CashflowService.UpdateScope.THIS_ONLY).occurrenceDate(occurrenceDate);
    }

    private static void assertStatusChangeRejected(CashflowService target, CashflowItem.Status to) {
        assertThatThrownBy(() -> target.update(7L, 41L, new UpdateBuilder().status(to).build()))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_STATUS_CHANGE));
    }

    private void assertUpdateInvalid(CashflowService target, CashflowService.UpdateCommand command, String field) {
        assertThatThrownBy(() -> target.update(7L, 41L, command))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> {
                    BusinessException business = (BusinessException) error;
                    assertThat(business.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
                    assertThat(fieldOf(business)).isEqualTo(field);
                });
    }

    /** 수정 요청을 필요한 필드만 채워 만든다. */
    private static final class UpdateBuilder {
        private CashflowItem.Direction type;
        private String title;
        private Long amount;
        private LocalDate date;
        private CashflowService.RecurrenceChange recurrence;
        private Long accountId;
        private Long userCardId;
        private String memo;
        private CashflowItem.Status status;
        private CashflowService.UpdateScope scope = CashflowService.UpdateScope.THIS_AND_FUTURE;
        private LocalDate occurrenceDate;

        UpdateBuilder type(CashflowItem.Direction value) {
            type = value;
            return this;
        }

        UpdateBuilder title(String value) {
            title = value;
            return this;
        }

        UpdateBuilder amount(Long value) {
            amount = value;
            return this;
        }

        UpdateBuilder date(LocalDate value) {
            date = value;
            return this;
        }

        UpdateBuilder recurrence(CashflowService.RecurrenceChange value) {
            recurrence = value;
            return this;
        }

        UpdateBuilder accountId(Long value) {
            accountId = value;
            return this;
        }

        UpdateBuilder userCardId(Long value) {
            userCardId = value;
            return this;
        }

        UpdateBuilder memo(String value) {
            memo = value;
            return this;
        }

        UpdateBuilder status(CashflowItem.Status value) {
            status = value;
            return this;
        }

        UpdateBuilder scope(CashflowService.UpdateScope value) {
            scope = value;
            return this;
        }

        UpdateBuilder occurrenceDate(LocalDate value) {
            occurrenceDate = value;
            return this;
        }

        CashflowService.UpdateCommand build() {
            return new CashflowService.UpdateCommand(type, title, amount, date, recurrence, null,
                    accountId, userCardId, memo, status, scope, occurrenceDate);
        }
    }

    private void rulesBetween(LocalDate from, LocalDate to, CashflowItem... rules) {
        when(items.findRulesForUser(7L, from, to, CashflowItem.ItemKind.SCHEDULED, CashflowItem.RecurrenceType.NONE,
                null, CashflowItem.Status.CONFIRMED)).thenReturn(List.of(rules));
    }

    private static CashflowItem saved(Long id, CashflowItem item) {
        ReflectionTestUtils.setField(item, "id", id);
        return item;
    }

    private static CashflowOccurrence occurrence(Long id, CashflowItem rule, LocalDate occurrenceDate) {
        CashflowOccurrence occurrence = CashflowOccurrence.of(rule, occurrenceDate);
        ReflectionTestUtils.setField(occurrence, "id", id);
        return occurrence;
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

    private void assertListInvalid(LocalDate from, LocalDate to, String field) {
        assertThatThrownBy(() -> service.list(7L, from, to, null, null, true))
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

    private static LinkedAccountRef account(Long accountId) {
        return new LinkedAccountRef(accountId, "088", "1234");
    }

    private static UserCardRef card(Long userCardId, CardIssuerStatus issuerStatus) {
        return new UserCardRef(userCardId, 1L, 11L, "5678", CardRegistrationStatus.ACTIVE, issuerStatus);
    }

    private static CashflowService.Item rule(LocalDate first, CashflowItem.RecurrenceType recurrence, LocalDate end) {
        return new CashflowService.Item(1L, 11L, null, "규칙", CashflowItem.Direction.EXPENSE, 10000L,
                first, recurrence, end,
                recurrence == CashflowItem.RecurrenceType.MONTHLY ? Integer.valueOf(first.getDayOfMonth()) : null,
                null, CashflowItem.Status.CONFIRMED, null);
    }

    private CashflowService.CreateCommand command(Long amount, Long accountId, Long cardId) {
        return new CashflowService.CreateCommand("월세", CashflowItem.Direction.EXPENSE,
                amount, tomorrow, CashflowItem.RecurrenceType.NONE, null, null, null,
                accountId, cardId, "UTILITIES", null);
    }
}
