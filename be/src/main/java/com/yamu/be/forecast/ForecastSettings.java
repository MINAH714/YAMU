package com.yamu.be.forecast;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * 잔고예보 공통 설정의 저장 형태({@code app_user.forecast_settings} JSON). 명세 「잔고예보 설정 조회」「수정」.
 * 기준 계좌는 여기 두지 않는다. {@code linked_account.is_primary} 하나만 원본으로 쓴다.
 *
 * @param horizon               예보 기간 기준(다음 월급일 전까지·고정 일수)
 * @param defaultDays           고정 일수 기본값(7·14·30)
 * @param alertThreshold        안전 잔액(원). 통합·계좌별 위험 판단 기준
 * @param incomeCashflowItemId  월급으로 쓸 매월 수입 항목. 없으면 null
 * @param dailyLivingCostTarget 하루 생활비 목표(원). 없으면 null
 * @param includeCandidates     반복 지출 후보를 예상 선에 넣을지
 * @param conservativeLine      보수적 선을 보일지
 * @param updatedAt             마지막 수정 UTC 시각. 한 번도 수정하지 않았으면 null
 */
record ForecastSettings(BalanceForecast.Until horizon, int defaultDays, long alertThreshold, Long incomeCashflowItemId,
        Long dailyLivingCostTarget, boolean includeCandidates, boolean conservativeLine, LocalDateTime updatedAt) {

    static final Set<Integer> ALLOWED_DAYS = Set.of(7, 14, 30);
    /** 명세 타입이 int라 설정 금액의 상한은 int 최댓값이다. */
    static final long MAX_AMOUNT = Integer.MAX_VALUE;

    /** 한 번도 저장하지 않은 사용자의 값(명세 기본값). 안전 잔액 100,000원은 명세의 현행 가정이다. */
    static final ForecastSettings DEFAULTS = new ForecastSettings(BalanceForecast.Until.NEXT_INCOME, 30, 100_000L,
            null, null, true, true, null);
}
