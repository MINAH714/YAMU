package com.yamu.be.forecast;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.payment.PaymentPlan;
import com.yamu.be.payment.PaymentTerms;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 가상 구매 시뮬레이션 요청 본문(명세 「가상 구매 시뮬레이션」). 금액·개월 수·기간은 소수를 잘라 쓰지 않도록 BigDecimal로 받고,
 * 결제 방식·개월 수는 보냈는지(null 포함)를 따로 기억한다. 금액 오류를 할부 오류보다 먼저 본다(POS 결제 요청과 같은 순서).
 */
public class SimulationRequest {
    /** 시뮬레이션 금액 상한(원). 계산 넘침을 막는다(2026-11-02 !61 리뷰 약속). */
    static final long MAX_AMOUNT = 100_000_000_000L;
    static final int MAX_PRODUCT_NAME = 150;
    static final int MAX_CODE = 64;

    private List<Long> accountIds;
    private boolean includeAccountDetails;
    private BigDecimal amount;
    private String productName;
    private String productCategoryCode;
    private String merchantCategoryCode;
    private Long merchantId;
    private LocalDate purchaseDate;
    private Long userCardId;
    private String plan;
    @JsonIgnore
    private boolean planPresent;
    private BigDecimal installmentMonths;
    @JsonIgnore
    private boolean installmentMonthsPresent;
    private BigDecimal days;

    @JsonSetter("accountIds")
    public void setAccountIds(List<Long> accountIds) {
        this.accountIds = accountIds;
    }

    @JsonSetter("includeAccountDetails")
    public void setIncludeAccountDetails(Boolean includeAccountDetails) {
        this.includeAccountDetails = Boolean.TRUE.equals(includeAccountDetails);
    }

    @JsonSetter("amount")
    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    @JsonSetter("productName")
    public void setProductName(String productName) {
        this.productName = productName;
    }

    @JsonSetter("productCategoryCode")
    public void setProductCategoryCode(String productCategoryCode) {
        this.productCategoryCode = productCategoryCode;
    }

    @JsonSetter("merchantCategoryCode")
    public void setMerchantCategoryCode(String merchantCategoryCode) {
        this.merchantCategoryCode = merchantCategoryCode;
    }

    @JsonSetter("merchantId")
    public void setMerchantId(Long merchantId) {
        this.merchantId = merchantId;
    }

    @JsonSetter("purchaseDate")
    public void setPurchaseDate(LocalDate purchaseDate) {
        this.purchaseDate = purchaseDate;
    }

    @JsonSetter("userCardId")
    public void setUserCardId(Long userCardId) {
        this.userCardId = userCardId;
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

    @JsonSetter("days")
    public void setDays(BigDecimal days) {
        this.days = days;
    }

    List<Long> accountIds() {
        return accountIds;
    }

    boolean includeAccountDetails() {
        return includeAccountDetails;
    }

    String productName() {
        return productName;
    }

    String productCategoryCode() {
        return blankToNull(productCategoryCode);
    }

    String merchantCategoryCode() {
        return blankToNull(merchantCategoryCode);
    }

    Long merchantId() {
        return merchantId;
    }

    LocalDate purchaseDate() {
        return purchaseDate;
    }

    Long userCardId() {
        return userCardId;
    }

    /**
     * 결제 조건으로 바꾼다. 금액(INVALID_AMOUNT) → 결제 방식·개월 수(INVALID_INSTALLMENT) 순서로 본다.
     * 범위·최소 금액은 {@link PaymentTerms}가 본다.
     */
    PaymentTerms toTerms() {
        if (amount == null) {
            throw PaymentTerms.invalid(ErrorCode.INVALID_AMOUNT, "amount", "구매 금액을 보내 주세요.");
        }
        long won;
        try {
            won = amount.longValueExact();
        } catch (ArithmeticException e) {
            throw PaymentTerms.invalid(ErrorCode.INVALID_AMOUNT, "amount", "구매 금액은 원 단위 정수로 보내 주세요.");
        }
        if (won <= 0) {
            throw PaymentTerms.invalid(ErrorCode.INVALID_AMOUNT, "amount", "구매 금액은 1원 이상이어야 합니다.");
        }
        if (won > MAX_AMOUNT) {
            throw PaymentTerms.invalid(ErrorCode.INVALID_AMOUNT, "amount", "구매 금액은 1,000억 원까지 입력할 수 있습니다.");
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
        Integer months = null;
        if (installmentMonths != null) {
            try {
                months = installmentMonths.intValueExact();
            } catch (ArithmeticException e) {
                throw PaymentTerms.invalid(ErrorCode.INVALID_INSTALLMENT, "installmentMonths",
                        "할부 개월 수는 정수로 보내 주세요.");
            }
        }
        return new PaymentTerms(won, paymentPlan, months);
    }

    /** 비교 기간(7·14·30). 없으면 null(잔고예보 설정). 정수가 아니면 거절한다. 값의 범위는 잔고예보 기간 검사가 본다. */
    Integer days() {
        if (days == null) {
            return null;
        }
        try {
            return days.intValueExact();
        } catch (ArithmeticException e) {
            throw PaymentTerms.invalid(ErrorCode.INVALID_REQUEST, "days", "예보 기간은 7, 14, 30일 중에서 골라 주세요.");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
