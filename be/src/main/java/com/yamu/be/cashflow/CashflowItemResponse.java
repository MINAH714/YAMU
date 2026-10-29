package com.yamu.be.cashflow;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;

/**
 * 가계부 항목 응답. 필드 이름은 API 명세서 「가계부 항목 등록」「가계부 항목 목록」을 따른다.
 *
 * <p>occurrenceDate는 등록 응답과 expand=false 목록에서는 규칙의 첫 발생일, 펼친 목록에서는 규칙이 정한 원래 회차다.
 * 이번 회차 수정·삭제 요청에 그대로 돌려보내는 식별값이다. date는 실제 예정일로, 이번 회차만 날짜를 옮겼을 때만 다르다.
 * productCategoryCode·productName은 구매 계획용 필드라 구매 계획 구현 전까지 null이다.
 */
public record CashflowItemResponse(
        Long cashflowItemId,
        CashflowItem.Direction type,
        String title,
        Long amount,
        LocalDate occurrenceDate,
        LocalDate date,
        Recurrence recurrence,
        String merchantCategoryCode,
        String productCategoryCode,
        String productName,
        Long accountId,
        Long userCardId,
        String source,
        CashflowItem.Status status,
        String memo) {

    public record Recurrence(CashflowItem.RecurrenceType frequency, Integer dayOfMonth,
            DayOfWeek dayOfWeek, LocalDate endDate) {
    }

    /** 목록 응답. 반복 지출 탐지 구현 전이라 candidateCount는 0이다. */
    public record ListResponse(List<CashflowItemResponse> items, int candidateCount) {
    }

    static CashflowItemResponse from(CashflowService.Item item) {
        return from(item, item.scheduledDate(), item.scheduledDate());
    }

    static CashflowItemResponse from(CashflowService.Entry entry) {
        return from(entry.item(), entry.occurrenceDate(), entry.date());
    }

    private static CashflowItemResponse from(CashflowService.Item item, LocalDate occurrenceDate, LocalDate date) {
        return new CashflowItemResponse(item.id(), item.direction(), item.title(), item.amount(),
                occurrenceDate, date, recurrenceOf(item), item.merchantCategoryCode(), null, null,
                item.accountId(), item.userCardId(), "MANUAL", item.status(), item.memo());
    }

    private static Recurrence recurrenceOf(CashflowService.Item item) {
        return switch (item.recurrenceType()) {
            case MONTHLY -> new Recurrence(item.recurrenceType(), item.scheduledDate().getDayOfMonth(),
                    null, item.recurrenceEndDate());
            case WEEKLY -> new Recurrence(item.recurrenceType(), null,
                    item.scheduledDate().getDayOfWeek(), item.recurrenceEndDate());
            default -> null;
        };
    }
}
