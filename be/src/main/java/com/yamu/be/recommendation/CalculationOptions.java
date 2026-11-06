package com.yamu.be.recommendation;

/** 서버 내부 시뮬레이션 전용 옵션. 실제 결제 호출은 기존 2인자 calculate를 사용한다. */
public record CalculationOptions(boolean excludeBrandDependentBenefits) {
    public static final CalculationOptions DEFAULT = new CalculationOptions(false);
}
