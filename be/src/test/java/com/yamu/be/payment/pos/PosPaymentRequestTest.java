package com.yamu.be.payment.pos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.payment.PaymentPlan;
import com.yamu.be.payment.PaymentTerms;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** POS 본문의 plan·installmentMonths "칸을 보냈는데 null"과 "안 보냄"을 구분한다(명세 「결제 방식·할부 요청 검증」). */
class PosPaymentRequestTest {

    final ObjectMapper json = new ObjectMapper();

    @Test
    void plan을_안_보내면_일시불이다() throws Exception {
        PaymentTerms terms = parse("""
                {"barcodeToken":"1234567890123456","merchantId":301,"posCode":"POS-TEST-CAFE","amount":15000}
                """).toTerms();

        assertThat(terms.plan()).isEqualTo(PaymentPlan.LUMP_SUM);
        assertThat(terms.amount()).isEqualTo(15_000);
        assertThat(terms.installmentMonths()).isNull();
    }

    @Test
    void plan을_null로_보내면_INVALID_INSTALLMENT() throws Exception {
        PosPaymentRequest request = parse("""
                {"barcodeToken":"1234567890123456","merchantId":301,"posCode":"POS-TEST-CAFE","amount":15000,"plan":null}
                """);

        assertInvalid(request, ErrorCode.INVALID_INSTALLMENT, "plan");
    }

    @Test
    void 지원하지_않는_plan은_INVALID_INSTALLMENT() throws Exception {
        PosPaymentRequest request = parse("""
                {"barcodeToken":"1234567890123456","merchantId":301,"posCode":"POS-TEST-CAFE","amount":15000,"plan":"CASH"}
                """);

        assertInvalid(request, ErrorCode.INVALID_INSTALLMENT, "plan");
    }

    @Test
    void 일시불에_개월_수_칸을_보내면_null이어도_INVALID_INSTALLMENT() throws Exception {
        PosPaymentRequest withNull = parse("""
                {"barcodeToken":"1234567890123456","merchantId":301,"posCode":"POS-TEST-CAFE","amount":15000,"installmentMonths":null}
                """);
        PosPaymentRequest withValue = parse("""
                {"barcodeToken":"1234567890123456","merchantId":301,"posCode":"POS-TEST-CAFE","amount":15000,"plan":"LUMP_SUM","installmentMonths":3}
                """);

        assertInvalid(withNull, ErrorCode.INVALID_INSTALLMENT, "installmentMonths");
        assertInvalid(withValue, ErrorCode.INVALID_INSTALLMENT, "installmentMonths");
    }

    @Test
    void 할부는_개월_수와_함께_결제_조건이_된다() throws Exception {
        PaymentTerms terms = parse("""
                {"barcodeToken":"1234567890123456","merchantId":301,"posCode":"POS-TEST-CAFE","amount":100000,"plan":"INSTALLMENT","installmentMonths":3}
                """).toTerms();

        assertThat(terms.plan()).isEqualTo(PaymentPlan.INSTALLMENT);
        assertThat(terms.installmentMonths()).isEqualTo(3);
        assertThat(terms.installmentMonthsOrOne()).isEqualTo(3);
    }

    @Test
    void 금액이_정수가_아니면_INVALID_AMOUNT이고_할부_오류보다_먼저다() throws Exception {
        PosPaymentRequest request = parse("""
                {"barcodeToken":"1234567890123456","merchantId":301,"posCode":"POS-TEST-CAFE","amount":15000.5,"plan":"INSTALLMENT","installmentMonths":1}
                """);

        assertInvalid(request, ErrorCode.INVALID_AMOUNT, "amount");
    }

    @Test
    void 금액이_0원이면_INVALID_AMOUNT() throws Exception {
        PosPaymentRequest request = parse("""
                {"barcodeToken":"1234567890123456","merchantId":301,"posCode":"POS-TEST-CAFE","amount":0}
                """);

        assertInvalid(request, ErrorCode.INVALID_AMOUNT, "amount");
    }

    @Test
    void 금액이_0원이면_잘못된_plan보다_INVALID_AMOUNT가_먼저다() throws Exception {
        PosPaymentRequest request = parse("""
                {"barcodeToken":"1234567890123456","merchantId":301,"posCode":"POS-TEST-CAFE","amount":0,"plan":"CASH"}
                """);

        assertInvalid(request, ErrorCode.INVALID_AMOUNT, "amount");
    }

    @Test
    void 금액이_음수면_일시불에_개월_수를_보낸_오류보다_INVALID_AMOUNT가_먼저다() throws Exception {
        PosPaymentRequest request = parse("""
                {"barcodeToken":"1234567890123456","merchantId":301,"posCode":"POS-TEST-CAFE","amount":-100,"installmentMonths":3}
                """);

        assertInvalid(request, ErrorCode.INVALID_AMOUNT, "amount");
    }

    @Test
    void 할부_개월_수가_소수면_잘라_쓰지_않고_INVALID_INSTALLMENT() throws Exception {
        PosPaymentRequest request = parse("""
                {"barcodeToken":"1234567890123456","merchantId":301,"posCode":"POS-TEST-CAFE","amount":100000,"plan":"INSTALLMENT","installmentMonths":2.9}
                """);

        assertInvalid(request, ErrorCode.INVALID_INSTALLMENT, "installmentMonths");
    }

    @Test
    void 할부_개월_수가_정수로_표현된_소수면_받는다() throws Exception {
        PosPaymentRequest request = parse("""
                {"barcodeToken":"1234567890123456","merchantId":301,"posCode":"POS-TEST-CAFE","amount":100000,"plan":"INSTALLMENT","installmentMonths":3.0}
                """);

        assertThat(request.toTerms().installmentMonths()).isEqualTo(3);
    }

    private PosPaymentRequest parse(String body) throws Exception {
        return json.readValue(body, PosPaymentRequest.class);
    }

    @SuppressWarnings("unchecked")
    private static void assertInvalid(PosPaymentRequest request, ErrorCode code, String field) {
        assertThatThrownBy(request::toTerms)
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.getErrorCode()).isEqualTo(code);
                    List<Map<String, String>> errors = (List<Map<String, String>>) be.getDetails().get("fieldErrors");
                    assertThat(errors).extracting(m -> m.get("field")).containsExactly(field);
                });
    }
}
