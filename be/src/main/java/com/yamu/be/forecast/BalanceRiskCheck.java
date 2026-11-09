package com.yamu.be.forecast;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 잔고 위험 알림(Task 9.3.2, S15P31E103-377)이 쓰는 판정만 하는 읽기 창구. 잔고예보 조회와 같은 재료({@link ForecastService#prepare})·
 * 같은 계산({@link ForecastCalculator}·{@link RiskDetector})으로 가장 이른 위험 구간을 돌려준다.
 *
 * <p>통합 잔액 선과 계좌별 선을 모두 본다. 잔고예보 최종 상태와 같은 기준이다(명세 예시: 통합은 안전해도 한 계좌가 위험하면 DANGER).
 * 첫날이 같으면 통합 구간을 먼저 고른다.
 *
 * <p>잔고예보 조회({@link ForecastService#forecast})를 부르지 않는 이유: 조회는 응답 직전에 예상 생활비 이력({@link LivingCostPredictionLog})을
 * 남긴다. 알림 작업이 매일 사용자마다 이력을 쌓으면 "사용자가 본 예측"과 실제를 비교하는 이력이 흐려진다. 가상 구매처럼 재료만 쓴다.
 *
 * <p>위험 수준·구간 타입({@link RiskDetector})이 패키지 밖에 공개되지 않아, 알림에 필요한 값만 담은 {@link Risk}로 바꿔 준다.
 * 판정 기준은 잔고예보 화면과 같다: 기간은 잔고예보 설정의 기간 기준, 안전 잔액은 설정의 {@code alertThreshold}, 잔액은 캐시(10분)를 쓴다.
 */
@Component
public class BalanceRiskCheck {

    /** 한 번에 판정할 수 있는 계좌 수(잔고예보 조회와 같다). */
    public static final int MAX_ACCOUNTS = ForecastService.MAX_ACCOUNTS;

    private final ForecastService forecasts;

    public BalanceRiskCheck(ForecastService forecasts) {
        this.forecasts = forecasts;
    }

    /**
     * 통합·계좌별 선 중 가장 이른 위험 구간.
     *
     * @param accountIds 예보할 연결 계좌(최대 {@value #MAX_ACCOUNTS}개). 남의·연결 해제 계좌면 BE 1이 404로 거절한다
     * @return 위험 구간이 없으면 empty
     */
    public Optional<Risk> firstRisk(Long userId, List<Long> accountIds) {
        LocalDate today = forecasts.today();
        ForecastService.Prepared prepared = forecasts.prepare(userId, accountIds, null, null, false, today);
        long threshold = prepared.alertThreshold();
        ForecastCalculator.Projection projection = ForecastCalculator.project(prepared.startBalances(),
                prepared.events(), prepared.horizon().from(), prepared.horizon().to(), prepared.dailyLiving());

        List<Risk> risks = new ArrayList<>();
        RiskDetector.assess(projection.total(), threshold).windows()
                .forEach(window -> risks.add(risk(today, null, window, threshold)));
        for (Long accountId : prepared.selected()) {
            RiskDetector.assess(projection.byAccount().get(accountId), threshold).windows()
                    .forEach(window -> risks.add(risk(today, accountId, window, threshold)));
        }
        // 정렬은 안정적이라 첫날이 같으면 먼저 넣은 통합 구간이 앞선다
        return risks.stream().min(Comparator.comparing(Risk::from));
    }

    private static Risk risk(LocalDate today, Long accountId, RiskDetector.RiskWindow window, long threshold) {
        return new Risk(today, accountId, window.from(), window.to(), window.level().name(), window.lowestBalance(),
                window.shortfall(), threshold);
    }

    /**
     * 가장 이른 위험 구간.
     *
     * @param today         판정한 한국 날짜(예보 시작일)
     * @param accountId     계좌 하나의 위험이면 그 계좌, 통합 잔액의 위험이면 null
     * @param from          구간 첫날(한국 날짜). 오늘이면 이미 안전 잔액 아래다
     * @param to            구간 마지막 날
     * @param level         {@code WARNING}(예상·보수적 선) 또는 {@code DANGER}(확정 선)
     * @param lowestBalance 구간 최저 잔액(원)
     * @param shortfall     안전 잔액보다 모자란 최대 금액(원, 양수)
     * @param threshold     안전 잔액(원)
     */
    public record Risk(LocalDate today, Long accountId, LocalDate from, LocalDate to, String level,
            long lowestBalance, long shortfall, long threshold) {}
}
