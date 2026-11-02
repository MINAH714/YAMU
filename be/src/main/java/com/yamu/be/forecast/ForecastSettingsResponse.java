package com.yamu.be.forecast;

import java.time.LocalDateTime;

/**
 * 잔고예보 설정 조회·수정 응답(명세 「잔고예보 설정 조회」). 수정도 같은 모양이다.
 *
 * @param baseAccountId         기준 계좌(홈 미리보기용, {@code linked_account.is_primary}). 조회 전용. 없으면 null
 * @param baseAccount           기준 계좌 표시 정보. 없으면 null
 * @param horizon               NEXT_INCOME(다음 월급일 전까지)·DAYS
 * @param defaultDays           고정 일수 기본값(7·14·30)
 * @param alertThreshold        안전 잔액(원)
 * @param income                월급 항목. 지정하지 않았거나 더는 받을 월급이 없는 항목(삭제·종료)이면 null
 * @param dailyLivingCostTarget 하루 생활비 목표(원). 없으면 null
 * @param updatedAt             마지막 수정 UTC 시각. 한 번도 수정하지 않았으면 null
 */
public record ForecastSettingsResponse(Long baseAccountId, BaseAccount baseAccount, BalanceForecast.Until horizon,
        int defaultDays, long alertThreshold, Income income, Long dailyLivingCostTarget, boolean includeCandidates,
        boolean conservativeLine, LocalDateTime updatedAt) {

    /** @param maskedAccountNo 계좌 API와 같은 형식(****-****-****-1234) */
    public record BaseAccount(Long accountId, String bankName, String maskedAccountNo) {}

    /**
     * 월급 항목. 수정으로 이어진 규칙이 있으면 가장 최근 규칙이다.
     *
     * @param amount     금액(원). 금액 미정 항목이면 null
     * @param dayOfMonth 매월 들어오는 날(1~31, 짧은 달은 말일)
     */
    public record Income(Long cashflowItemId, String title, Long amount, int dayOfMonth, Long accountId) {}
}
