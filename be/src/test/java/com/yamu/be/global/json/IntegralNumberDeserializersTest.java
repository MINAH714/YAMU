package com.yamu.be.global.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.yamu.be.cashflow.CreateCashflowItemRequest;
import com.yamu.be.cashflow.UpdateCashflowItemRequest;
import org.junit.jupiter.api.Test;

/** 정수 칸 읽기(S15P31E103-318): 소수점 아래가 있으면 잘라 쓰지 않고 거절한다. DB·스프링 없음. */
class IntegralNumberDeserializersTest {
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void 소수점_아래가_있는_금액_계좌_카드_반복일은_거절한다() {
        for (String field : new String[] {"\"amount\": 500000.9", "\"accountId\": 3.9", "\"userCardId\": 7.2",
                "\"recurrence\": {\"frequency\": \"MONTHLY\", \"dayOfMonth\": 15.7}"}) {
            assertThatThrownBy(() -> json.readValue(create(field), CreateCashflowItemRequest.class))
                    .as(field).isInstanceOf(InvalidFormatException.class);
        }
        assertThatThrownBy(() -> json.readValue("{\"amount\": 1000.5}", UpdateCashflowItemRequest.class))
                .isInstanceOf(InvalidFormatException.class);
    }

    @Test
    void 값이_정수인_소수_표기와_정수_문자열_null은_그대로_받는다() throws Exception {
        CreateCashflowItemRequest request = json.readValue(create(
                "\"amount\": 500000.0, \"accountId\": 3e0, \"recurrence\": {\"frequency\": \"MONTHLY\", \"dayOfMonth\": 15.00}"),
                CreateCashflowItemRequest.class);

        assertThat(request.amount()).isEqualTo(500_000L);
        assertThat(request.accountId()).isEqualTo(3L);
        assertThat(request.recurrence().dayOfMonth()).isEqualTo(15);
        assertThat(json.readValue("{\"amount\": 1e3, \"userCardId\": null}", UpdateCashflowItemRequest.class))
                .extracting(UpdateCashflowItemRequest::amount, UpdateCashflowItemRequest::userCardId)
                .containsExactly(1_000L, null);
        assertThat(json.readValue("{\"amount\": \"2500\"}", UpdateCashflowItemRequest.class).amount()).isEqualTo(2_500L);
    }

    @Test
    void 정수_범위를_넘는_소수_표기는_잘라_쓰지_않고_거절한다() {
        for (String amount : new String[] {"1e19", "1e999999999", "-9.3e18"}) {
            assertThatThrownBy(() -> json.readValue("{\"amount\": " + amount + "}", UpdateCashflowItemRequest.class))
                    .as(amount).isInstanceOf(InvalidFormatException.class);
        }
        assertThatThrownBy(() -> json.readValue(create("\"recurrence\": {\"frequency\": \"MONTHLY\", \"dayOfMonth\": 3e9}"),
                CreateCashflowItemRequest.class)).isInstanceOf(InvalidFormatException.class);
    }

    private static String create(String field) {
        return "{\"type\": \"EXPENSE\", \"title\": \"월세\", \"amount\": 500000, \"date\": \"2026-11-10\", " + field + "}";
    }
}
