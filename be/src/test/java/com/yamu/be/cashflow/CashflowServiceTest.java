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
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
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
    @Mock AccountQueryService accounts;
    @Mock CardQueryService cards;

    CashflowService service;
    LocalDate today;
    LocalDate tomorrow;

    @BeforeEach
    void setUp() {
        service = new CashflowService(items, occurrences, accounts, cards, Clock.systemUTC());
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
                        CashflowItem.RecurrenceType.NONE, null, null, null)));

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
                today, CashflowItem.RecurrenceType.WEEKLY, null, null, null);
        CashflowItem once = new CashflowItem(7L, 11L, null, "병원", CashflowItem.Direction.EXPENSE, 30000L,
                today.plusDays(8), CashflowItem.RecurrenceType.NONE, null, null, null);
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
                10000L, LocalDate.of(2026, 11, 2), CashflowItem.RecurrenceType.WEEKLY, null, null, null));
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
                10000L, LocalDate.of(2026, 11, 2), CashflowItem.RecurrenceType.WEEKLY, null, null, null));
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
                12000L, LocalDate.of(2026, 11, 5), CashflowItem.RecurrenceType.MONTHLY, null, null, null));
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
    void 기간_밖_규칙에서_옮겨_들어온_회차도_목록_조건을_걸어_보여_준다() {
        LocalDate from = LocalDate.of(2026, 12, 1);
        LocalDate to = LocalDate.of(2026, 12, 31);
        CashflowItem ended = saved(41L, new CashflowItem(7L, 11L, null, "월세", CashflowItem.Direction.EXPENSE,
                500000L, LocalDate.of(2026, 10, 25), CashflowItem.RecurrenceType.MONTHLY,
                LocalDate.of(2026, 11, 30), null, null));
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
                first, recurrence, end, null, CashflowItem.Status.CONFIRMED, null);
    }

    private CashflowService.CreateCommand command(Long amount, Long accountId, Long cardId) {
        return new CashflowService.CreateCommand("월세", CashflowItem.Direction.EXPENSE,
                amount, tomorrow, CashflowItem.RecurrenceType.NONE, null, null, null,
                accountId, cardId, "UTILITIES", null);
    }
}
