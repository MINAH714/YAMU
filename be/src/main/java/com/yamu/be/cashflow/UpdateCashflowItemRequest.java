package com.yamu.be.cashflow;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.yamu.be.global.json.IntegralNumberDeserializers;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import java.time.LocalDate;

/**
 * PATCH /api/cashflow-items/{id} 요청. 필드 이름은 API 명세서 「가계부 항목 수정」을 따르고 모두 선택이다.
 *
 * <p>보내지 않은 필드와 null은 그대로 둔다. 업종 코드·메모는 빈 문자열을 보내면 지운다.
 * 계좌나 카드 중 하나를 보내면 다른 하나는 비운다. recurrence를 보내면 반복 규칙을 통째로 바꾼다.
 * scope를 생략하면 THIS_AND_FUTURE이고, THIS_ONLY는 occurrenceDate(목록의 원래 회차)가 필요하다.
 */
public record UpdateCashflowItemRequest(
        // 바꿀 수 없다. 보내면 서비스가 400으로 거절한다.
        // enum 칸은 이름 문자열만 받는다. 숫자를 순번으로 읽지 않는다(StrictEnumDeserializer).
        @JsonDeserialize(using = StrictEnumDeserializer.class)
        CashflowItem.Direction type,

        String title,

        @Positive(message = "금액은 1원 이상이어야 합니다.")
        @Max(value = CashflowService.MAX_AMOUNT, message = CashflowService.MAX_AMOUNT_MESSAGE)
        @JsonDeserialize(using = IntegralNumberDeserializers.LongValue.class)
        Long amount,

        LocalDate date,

        @Valid
        CreateCashflowItemRequest.Recurrence recurrence,

        String merchantCategoryCode,

        @Positive(message = "올바른 계좌를 선택해 주세요.")
        @JsonDeserialize(using = IntegralNumberDeserializers.LongValue.class)
        Long accountId,

        @Positive(message = "올바른 카드를 선택해 주세요.")
        @JsonDeserialize(using = IntegralNumberDeserializers.LongValue.class)
        Long userCardId,

        String memo,

        // 탐지 후보(CANDIDATE)를 CONFIRMED(확인)나 IGNORED(무시)로 바꿀 때만 쓴다.
        @JsonDeserialize(using = StrictEnumDeserializer.class)
        CashflowItem.Status status,

        @JsonDeserialize(using = StrictEnumDeserializer.class)
        CashflowService.UpdateScope scope,

        LocalDate occurrenceDate) {

    CashflowService.UpdateCommand toCommand() {
        return new CashflowService.UpdateCommand(type, title, amount, date,
                recurrence == null ? null : new CashflowService.RecurrenceChange(recurrence.frequency(),
                        recurrence.dayOfMonth(), recurrence.dayOfWeek(), recurrence.endDate()),
                merchantCategoryCode, accountId, userCardId, memo, status,
                scope == null ? CashflowService.UpdateScope.THIS_AND_FUTURE : scope, occurrenceDate);
    }
}
