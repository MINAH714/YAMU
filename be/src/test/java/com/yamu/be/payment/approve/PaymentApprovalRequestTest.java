package com.yamu.be.payment.approve;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PaymentApprovalRequestTest {

    final ObjectMapper json = new ObjectMapper();

    @Test
    void 본문이_없거나_비어_있으면_카드_생략이다() throws Exception {
        assertThat(PaymentApprovalRequest.parse(null).userCardId()).isNull();
        assertThat(PaymentApprovalRequest.parse(json.readTree("null")).userCardId()).isNull();
        assertThat(PaymentApprovalRequest.parse(json.readTree("{}")).userSelected()).isFalse();
        assertThat(PaymentApprovalRequest.parse(json.readTree("{\"userCardId\": null}")).userSelected()).isFalse();
    }

    @Test
    void 카드를_고르면_그_번호를_쓴다() throws Exception {
        PaymentApprovalRequest request = PaymentApprovalRequest.parse(json.readTree("{\"userCardId\": 15}"));

        assertThat(request.userCardId()).isEqualTo(15L);
        assertThat(request.userSelected()).isTrue();
    }

    @Test
    void plan이나_installmentMonths_칸이_있으면_값이_null이어도_INVALID_INSTALLMENT다() throws Exception {
        for (String body : List.of(
                "{\"plan\": \"INSTALLMENT\"}",
                "{\"plan\": null}",
                "{\"installmentMonths\": null}",
                "{\"installmentMonths\": 3}",
                "{\"userCardId\": 15, \"plan\": \"LUMP_SUM\", \"installmentMonths\": null}")) {
            JsonNode node = json.readTree(body);
            assertThatThrownBy(() -> PaymentApprovalRequest.parse(node))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_INSTALLMENT);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void 금지된_칸은_모두_fieldErrors에_순서대로_적힌다() throws Exception {
        JsonNode node = json.readTree("{\"installmentMonths\": null, \"plan\": null}");

        BusinessException e = (BusinessException) org.assertj.core.api.Assertions.catchThrowable(
                () -> PaymentApprovalRequest.parse(node));

        List<Map<String, String>> fieldErrors = (List<Map<String, String>>) e.getDetails().get("fieldErrors");
        assertThat(fieldErrors).extracting(m -> m.get("field")).containsExactly("plan", "installmentMonths");
    }

    @Test
    void 카드_번호가_양의_정수가_아니면_INVALID_REQUEST다() throws Exception {
        for (String body : List.of("{\"userCardId\": \"a\"}", "{\"userCardId\": 0}", "{\"userCardId\": -1}",
                "{\"userCardId\": 1.5}", "{\"userCardId\": true}")) {
            JsonNode node = json.readTree(body);
            assertThatThrownBy(() -> PaymentApprovalRequest.parse(node))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_REQUEST);
        }
    }

    @Test
    void 객체가_아닌_본문은_INVALID_REQUEST다() throws Exception {
        JsonNode node = json.readTree("[15]");

        assertThatThrownBy(() -> PaymentApprovalRequest.parse(node))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_REQUEST);
    }
}
