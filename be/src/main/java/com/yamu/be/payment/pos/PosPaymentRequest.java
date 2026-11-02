package com.yamu.be.payment.pos;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.payment.PaymentPlan;
import com.yamu.be.payment.PaymentTerms;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import lombok.Getter;

/**
 * POS 결제 요청 본문(API 명세 「POS 결제 요청 생성」).
 *
 * <p>record가 아니라 클래스인 이유: {@code plan}·{@code installmentMonths}는 "칸을 보냈는데 null"과 "칸을 안 보냄"을 구분해야 한다
 * (명세 「결제 방식·할부 요청 검증」: plan 생략은 일시불, null은 거절. 일시불에 개월 수 칸은 null이어도 거절).
 * 금액은 정수가 아닌 값을 400 INVALID_AMOUNT로 돌려주려고 BigDecimal로 받는다. 할부 개월 수도 2.9를 2로 바꿔 받지 않도록
 * BigDecimal로 받아 정수인지 직접 본다(Jackson은 기본으로 소수를 정수 칸에 잘라 넣는다).
 */
@Getter
public class PosPaymentRequest {

    @NotBlank(message = "바코드 토큰을 보내 주세요.")
    private String barcodeToken;

    @NotNull(message = "가맹점 번호(merchantId)를 보내 주세요.")
    private Long merchantId;

    @NotBlank(message = "POS 시나리오 코드(posCode)를 보내 주세요.")
    private String posCode;

    @NotNull(message = "결제 금액을 보내 주세요.")
    private BigDecimal amount;

    private String plan;

    @JsonIgnore
    private boolean planPresent;

    private BigDecimal installmentMonths;

    @JsonIgnore
    private boolean installmentMonthsPresent;

    @Size(max = 64, message = "주문번호는 64자까지입니다.")
    private String orderNo;

    @JsonSetter("barcodeToken")
    public void setBarcodeToken(String barcodeToken) {
        this.barcodeToken = barcodeToken;
    }

    @JsonSetter("merchantId")
    public void setMerchantId(Long merchantId) {
        this.merchantId = merchantId;
    }

    @JsonSetter("posCode")
    public void setPosCode(String posCode) {
        this.posCode = posCode;
    }

    @JsonSetter("amount")
    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    @JsonSetter("plan")
    public void setPlan(String plan) {
        this.plan = plan;
        this.planPresent = true;
    }

    @JsonSetter("installmentMonths")
    public void setInstallmentMonths(BigDecimal installmentMonths) {
        this.installmentMonths = installmentMonths;
        this.installmentMonthsPresent = true;
    }

    @JsonSetter("orderNo")
    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }

    /** 결제 조건으로 바꾼다. 금액 오류(INVALID_AMOUNT)를 할부 오류(INVALID_INSTALLMENT)보다 먼저 본다. */
    public PaymentTerms toTerms() {
        long won;
        try {
            won = amount.longValueExact();
        } catch (ArithmeticException e) {
            throw PaymentTerms.invalid(ErrorCode.INVALID_AMOUNT, "amount", "결제 금액은 원 단위 정수로 보내 주세요.");
        }
        // 금액 오류를 결제 방식·개월 수 오류보다 먼저 알린다(0원·음수와 잘못된 plan이 같이 와도 INVALID_AMOUNT)
        if (won <= 0) {
            throw PaymentTerms.invalid(ErrorCode.INVALID_AMOUNT, "amount", "결제 금액은 1원 이상이어야 합니다.");
        }
        PaymentPlan paymentPlan;
        if (!planPresent) {
            paymentPlan = PaymentPlan.LUMP_SUM;
        } else if (plan == null) {
            throw PaymentTerms.invalid(ErrorCode.INVALID_INSTALLMENT, "plan", "결제 방식은 LUMP_SUM 또는 INSTALLMENT입니다.");
        } else {
            try {
                paymentPlan = PaymentPlan.valueOf(plan);
            } catch (IllegalArgumentException e) {
                throw PaymentTerms.invalid(ErrorCode.INVALID_INSTALLMENT, "plan", "결제 방식은 LUMP_SUM 또는 INSTALLMENT입니다.");
            }
        }
        if (paymentPlan == PaymentPlan.LUMP_SUM && installmentMonthsPresent) {
            throw PaymentTerms.invalid(ErrorCode.INVALID_INSTALLMENT, "installmentMonths",
                    "일시불에는 할부 개월 수를 보낼 수 없습니다.");
        }
        return new PaymentTerms(won, paymentPlan, months());
    }

    /** 할부 개월 수. 정수가 아니면(2.9 등) 잘라 쓰지 않고 거절한다. 범위(2~12)는 {@link PaymentTerms}가 본다. */
    private Integer months() {
        if (installmentMonths == null) {
            return null;
        }
        try {
            return installmentMonths.intValueExact();
        } catch (ArithmeticException e) {
            throw PaymentTerms.invalid(ErrorCode.INVALID_INSTALLMENT, "installmentMonths", "할부 개월 수는 정수로 보내 주세요.");
        }
    }
}
