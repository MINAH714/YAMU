package com.yamu.be.forecast;

/**
 * TSB(Teunter·Syntetos·Babai 2011) 간헐 소비 예측. 날마다 "쓸 확률" d를 갱신하고(안 쓴 날도 낮춤), 쓴 날에만 쓴 날 금액 z를
 * 갱신한다. 하루 예측은 d × z다. 처음 쓴 날 전에는 0원으로 예측한다.
 *
 * <p>AI 생활비 예측을 쓸 수 없을 때(기록 14일 미만, AI 서버 실패)와 7일 이하 기간에 쓴다(docs/be/forecast.md 「AI 생활비 예측」).
 * 식과 계산 순서는 실험 저장소의 {@code forecast_ml.intermittent.tsb_daily}와 같아 같은 입력이면 같은 소수 값이 나온다.
 * 설정값은 실험의 검증 선택에서 가장 많이 골라진 α 0.3·β 0.2로 고정한다(2026-11-05 결정).
 */
final class TsbEstimator {
    /** 쓴 날 금액 z를 따라가는 정도. */
    static final double ALPHA = 0.3;
    /** 쓸 확률 d를 따라가는 정도. */
    static final double BETA = 0.2;

    private TsbEstimator() {
    }

    /**
     * 일별 소비로 마지막 날 기준 하루 예측을 낸다.
     *
     * @param spend 일별 소비(원, 0 이상). 오래된 날부터. 기록이 시작되기 전 날은 0원으로 넣어도 결과가 같다
     * @return 하루 예측(원, 소수)
     */
    static double daily(long[] spend) {
        double z = 0;
        double d = 0;
        boolean started = false;
        for (long amount : spend) {
            if (amount < 0) {
                throw new IllegalArgumentException("일별 소비는 0원 이상이어야 한다.");
            }
            boolean spent = amount > 0;
            if (spent) {
                z = started ? z + ALPHA * (amount - z) : amount;
            }
            started |= spent;
            d = started ? d + BETA * ((spent ? 1.0 : 0.0) - d) : 0.0;
        }
        return d * z;
    }
}
