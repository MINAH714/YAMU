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
 * paymentMatched는 그 줄의 회차가 실제 결제와 연결됐는지다(S15P31E103-393, 명세에 없음). 연결한 결제가 취소돼도
 * 연결을 풀기 전까지 true다. 펼친 목록과 값을 바꾸지 않은 수정 응답(규칙의 첫 회차)은 실제 연결 여부이고,
 * 등록 응답·펼치지 않은 목록·값을 바꾼 수정 응답은 연결이 있을 수 없거나 회차가 아니라 false다.
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
        CashflowItem.Source source,
        CashflowItem.Status status,
        RecurringExpenseDetector.Detection detection,
        String memo,
        boolean paymentMatched) {

    public record Recurrence(CashflowItem.RecurrenceType frequency, Integer dayOfMonth,
            DayOfWeek dayOfWeek, LocalDate endDate) {
    }

    /** 목록 응답. candidateCount는 확인 전 탐지 후보 수다(S15P31E103-349). */
    public record ListResponse(List<CashflowItemResponse> items, long candidateCount) {
    }

    static CashflowItemResponse from(CashflowService.Item item) {
        return from(item, item.scheduledDate(), item.scheduledDate(), null, false);
    }

    static CashflowItemResponse from(CashflowService.Entry entry) {
        return from(entry.item(), entry.occurrenceDate(), entry.date(), null, entry.matched());
    }

    /**
     * @param detection 이번 요청에서 탐지한 후보의 근거. 확인 전 후보의 첫 규칙에만 있고, 그 밖에는 null
     */
    static CashflowItemResponse from(CashflowService.Entry entry, RecurringExpenseDetector.Detection detection) {
        return from(entry.item(), entry.occurrenceDate(), entry.date(), detection, entry.matched());
    }

    private static CashflowItemResponse from(CashflowService.Item item, LocalDate occurrenceDate, LocalDate date,
            RecurringExpenseDetector.Detection detection, boolean paymentMatched) {
        return new CashflowItemResponse(item.id(), item.direction(), item.title(), item.amount(),
                occurrenceDate, date, recurrenceOf(item), item.merchantCategoryCode(), null, null,
                item.accountId(), item.userCardId(), item.source(), item.status(), detection, item.memo(),
                paymentMatched);
    }

    private static Recurrence recurrenceOf(CashflowService.Item item) {
        return switch (item.recurrenceType()) {
            case MONTHLY -> new Recurrence(item.recurrenceType(), item.recurrenceDayOfMonth(),
                    null, item.recurrenceEndDate());
            case WEEKLY -> new Recurrence(item.recurrenceType(), null,
                    item.scheduledDate().getDayOfWeek(), item.recurrenceEndDate());
            default -> null;
        };
    }
}
