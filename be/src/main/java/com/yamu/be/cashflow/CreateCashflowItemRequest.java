package com.yamu.be.cashflow;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.DayOfWeek;
import java.time.LocalDate;

/** POST /api/cashflow-items 요청. 필드 이름은 API 명세서 「가계부 항목 등록」을 따른다. */
public record CreateCashflowItemRequest(
        // enum 칸은 이름 문자열만 받는다. 숫자를 순번으로 읽지 않는다(StrictEnumDeserializer).
        @NotNull(message = "수입·지출 유형을 선택해 주세요.")
        @JsonDeserialize(using = StrictEnumDeserializer.class)
        CashflowItem.Direction type,

        // 길이는 앞뒤 공백을 뺀 뒤 서비스에서 검사한다.
        @NotBlank(message = "이름을 입력해 주세요.")
        String title,

        @NotNull(message = "금액을 입력해 주세요.")
        @Positive(message = "금액은 1원 이상이어야 합니다.")
        @Max(value = CashflowService.MAX_AMOUNT, message = CashflowService.MAX_AMOUNT_MESSAGE)
        @JsonDeserialize(using = IntegralNumberDeserializers.LongValue.class)
        Long amount,

        @NotNull(message = "첫 발생일을 입력해 주세요.")
        LocalDate date,

        @Valid
        Recurrence recurrence,

        // 업종 코드·메모의 길이는 앞뒤 공백을 빼고 빈 값을 NULL로 바꾼 뒤 서비스에서 검사한다.
        String merchantCategoryCode,

        @Positive(message = "올바른 계좌를 선택해 주세요.")
        @JsonDeserialize(using = IntegralNumberDeserializers.LongValue.class)
        Long accountId,

        @Positive(message = "올바른 카드를 선택해 주세요.")
        @JsonDeserialize(using = IntegralNumberDeserializers.LongValue.class)
        Long userCardId,

        String memo) {

    /** 반복 규칙. 생략하면 1회 항목이다. */
    public record Recurrence(
            @NotNull(message = "반복 주기를 선택해 주세요.")
            @JsonDeserialize(using = StrictEnumDeserializer.class)
            CashflowItem.RecurrenceType frequency,

            @Min(value = 1, message = "매월 반복일은 1~31이어야 합니다.")
            @Max(value = 31, message = "매월 반복일은 1~31이어야 합니다.")
            @JsonDeserialize(using = IntegralNumberDeserializers.IntegerValue.class)
            Integer dayOfMonth,

            // 숫자로 받으면 순번(0=MONDAY)으로 읽혀 ISO 요일 번호(1=월요일)와 하루 어긋난다.
            @JsonDeserialize(using = StrictEnumDeserializer.class)
            DayOfWeek dayOfWeek,

            LocalDate endDate) {
    }

    CashflowService.CreateCommand toCommand() {
        return new CashflowService.CreateCommand(title, type, amount, date,
                recurrence == null ? CashflowItem.RecurrenceType.NONE : recurrence.frequency(),
                recurrence == null ? null : recurrence.dayOfMonth(),
                recurrence == null ? null : recurrence.dayOfWeek(),
                recurrence == null ? null : recurrence.endDate(),
                accountId, userCardId, merchantCategoryCode, memo);
    }
}
