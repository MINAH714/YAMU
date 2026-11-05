package com.yamu.be.forecast;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 예보 기간의 예상 생활비를 계좌마다 AI 또는 TSB로 낸다(docs/be/forecast.md 「AI 생활비 예측」, 2026-11-05 결정).
 * 지난 3개월 실적 요약(평균·월별 합계·이번 달 속도)은 {@link LivingCostEstimator}가 따로 낸다.
 *
 * <ul>
 *   <li>입력: 계좌마다 어제까지 90일의 일별 체크카드 생활 소비. 모의 소비(SEED)와 실제 결제(REAL)를 날짜순으로 이어 붙인다.
 *       기록 시작(그 계좌의 첫 줄) 전과, 시작 뒤 줄이 없는 날은 0원이다. 다 확인하지 못한 날(complete=false)은 확인된 금액을 쓴다.</li>
 *   <li>방식: 기록이 {@value #AI_MIN_HISTORY_DAYS}일 이상이고 90일 안에 사용이 있으면 AI(14·30일 모델), 아니면 TSB.
 *       AI 서버가 없거나 실패·계약 위반이면 그 요청의 모든 계좌를 TSB로 계산한다.</li>
 *   <li>기간 n일 값: (0, 0)·(7, TSB 7일)·(14, AI 14일)·(30, AI 30일)을 직선으로 잇고 앞 점보다 낮은 점은 올린다.
 *       30일보다 길면 30일 값의 하루 평균 × n이다. TSB만 쓰는 계좌는 TSB 하루 예측 × n이다.</li>
 *   <li>잔고예보는 내일부터 N일 동안 생활비를 빼는데, 어제까지의 기록으로 한 예측은 오늘부터다. 그래서 오늘을 포함한 N+1일을
 *       예측하고 오늘 이미 쓴 금액을 뺀 뒤 N으로 나눠 하루 값을 만든다. 예상 생활비는 하루 값 × N이다(잔고예보 선과 같은 값).</li>
 *   <li>신뢰도는 기록 길이다: 60일 이상 HIGH(짧은 기록 평가에서 두 해 모두 통과한 길이), 14~59일 MEDIUM, 1~13일 LOW.
 *       어제까지 기록이 없으면 INSUFFICIENT이고 예상 생활비를 비운다(0원으로 정하지 않음).</li>
 * </ul>
 */
@Component
class LivingCostProjector {
    static final int HISTORY_DAYS = 90;
    static final int AI_MIN_HISTORY_DAYS = 14;
    static final int HIGH_CONFIDENCE_DAYS = 60;
    static final String METHOD_AI = "ML_V1";
    static final String METHOD_TSB = "TSB";
    static final String METHOD_MIXED = "MIXED";

    private final AiLivingCostClient ai;

    LivingCostProjector(AiLivingCostClient ai) {
        this.ai = ai;
    }

    /**
     * 선택 계좌마다 예보 기간의 예상 생활비를 내고 통합 값을 더한다.
     *
     * @param accountIds 선택 계좌(결과 순서)
     * @param days       일별 생활 소비. 어제까지 90일과 오늘을 덮어야 한다. 계약 검사는 {@link LivingCostEstimator}가 먼저 한다
     * @param today      한국 날짜 오늘
     * @param horizonDays 예보 일수 N(내일부터 N일 동안 생활비를 뺀다). 0이면 뺄 날이 없어 0원이다
     * @return 계좌별·통합 예상 생활비
     */
    Projection project(List<Long> accountIds, List<LivingCostReader.DailySpending> days, LocalDate today,
            int horizonDays) {
        LocalDate asOf = today.minusDays(1);
        LocalDate windowStart = asOf.minusDays(HISTORY_DAYS - 1);
        int predictDays = horizonDays + 1;

        Map<Long, History> histories = new LinkedHashMap<>();
        for (Long accountId : accountIds) {
            histories.put(accountId, history(accountId, days, windowStart, asOf, today));
        }

        Map<String, Long> keys = new LinkedHashMap<>();
        Map<String, long[]> aiInputs = new LinkedHashMap<>();
        if (horizonDays > 0 && predictDays > 7) {
            histories.forEach((accountId, history) -> {
                if (history.historyDays() >= AI_MIN_HISTORY_DAYS && history.hasSpending()) {
                    // 계좌번호·사용자 ID 대신 요청 안에서만 쓰는 순번을 보낸다.
                    String key = "a" + keys.size();
                    keys.put(key, accountId);
                    aiInputs.put(key, history.daily());
                }
            });
        }
        Map<Long, long[]> aiPoints = aiPoints(asOf, predictDays, keys, aiInputs);

        Map<Long, AccountProjection> byAccount = new LinkedHashMap<>();
        histories.forEach((accountId, history) ->
                byAccount.put(accountId, account(accountId, history, aiPoints.get(accountId), predictDays, horizonDays)));
        return new Projection(byAccount, total(byAccount.values()));
    }

    /** AI 14·30일 예측을 계좌별 [14일, 30일]로 돌려준다. 30일이 필요 없으면 30일 칸은 -1. 하나라도 실패하면 빈 값(모두 TSB). */
    private Map<Long, long[]> aiPoints(LocalDate asOf, int predictDays, Map<String, Long> keys,
            Map<String, long[]> inputs) {
        if (inputs.isEmpty() || !ai.enabled()) {
            return Map.of();
        }
        Optional<Map<String, Long>> h14 = ai.predict(asOf, 14, inputs);
        if (h14.isEmpty()) {
            return Map.of();
        }
        Optional<Map<String, Long>> h30 = Optional.empty();
        if (predictDays > 14) {
            h30 = ai.predict(asOf, 30, inputs);
            if (h30.isEmpty()) {
                return Map.of();
            }
        }
        Map<Long, long[]> out = new HashMap<>();
        for (Map.Entry<String, Long> key : keys.entrySet()) {
            long a30 = h30.map(values -> values.get(key.getKey())).orElse(-1L);
            out.put(key.getValue(), new long[] {h14.get().get(key.getKey()), a30});
        }
        return out;
    }

    private static History history(Long accountId, List<LivingCostReader.DailySpending> days, LocalDate windowStart,
            LocalDate asOf, LocalDate today) {
        long[] daily = new long[HISTORY_DAYS];
        LocalDate first = null;
        long spentToday = 0;
        boolean seed = false;
        for (LivingCostReader.DailySpending row : days) {
            if (!row.accountId().equals(accountId)) {
                continue;
            }
            if (row.date().equals(today)) {
                spentToday = Math.addExact(spentToday, row.checkCardAmount());
                continue;
            }
            if (first == null || row.date().isBefore(first)) {
                first = row.date();
            }
            if (row.date().isBefore(windowStart)) {
                continue;
            }
            int index = (int) ChronoUnit.DAYS.between(windowStart, row.date());
            daily[index] = Math.addExact(daily[index], row.checkCardAmount());
            seed |= row.origin() == LivingCostReader.Origin.SEED;
        }
        int historyDays = first == null ? 0
                : (int) Math.min(HISTORY_DAYS, ChronoUnit.DAYS.between(first, asOf) + 1);
        boolean hasSpending = false;
        for (long amount : daily) {
            hasSpending |= amount > 0;
        }
        return new History(daily, historyDays, hasSpending, spentToday, seed);
    }

    private static AccountProjection account(Long accountId, History history, long[] aiPoint, int predictDays,
            int horizonDays) {
        if (history.historyDays() == 0) {
            return AccountProjection.insufficient(accountId);
        }
        double tsbDaily = TsbEstimator.daily(history.daily());
        String method = aiPoint == null ? METHOD_TSB : METHOD_AI;
        long upcoming = aiPoint == null ? round(tsbDaily * predictDays)
                : interpolate(round(tsbDaily * 7), aiPoint[0], aiPoint[1], predictDays);
        long daily = horizonDays == 0 ? 0 : roundedDivide(Math.max(0, upcoming - history.spentToday()), horizonDays);
        LivingCostEstimator.Confidence confidence = history.historyDays() >= HIGH_CONFIDENCE_DAYS
                ? LivingCostEstimator.Confidence.HIGH
                : history.historyDays() >= AI_MIN_HISTORY_DAYS ? LivingCostEstimator.Confidence.MEDIUM
                : LivingCostEstimator.Confidence.LOW;
        return new AccountProjection(accountId, Math.multiplyExact(daily, horizonDays), daily, method, confidence,
                history.seed() ? LivingCostEstimator.DataOrigin.SEED : LivingCostEstimator.DataOrigin.REAL);
    }

    /**
     * (0, 0)·(7, t7)·(14, a14)·(30, a30)을 직선으로 이은 n일 값. 앞 점보다 낮은 점은 앞 점 높이로 올려 기간을 늘려도 줄지 않게 한다.
     * 30일보다 길면 30일 값의 하루 평균 × n. n이 14 이하이면 a30은 쓰지 않는다(-1이어도 된다).
     */
    static long interpolate(long t7, long a14, long a30, int n) {
        if (n < 1) {
            throw new IllegalArgumentException("기간은 1일 이상이어야 한다.");
        }
        long p14 = Math.max(a14, t7);
        if (n <= 7) {
            return roundedDivide(Math.multiplyExact(t7, n), 7);
        }
        if (n <= 14) {
            return t7 + roundedDivide(Math.multiplyExact(p14 - t7, n - 7), 7);
        }
        if (a30 < 0) {
            throw new IllegalArgumentException("14일보다 긴 기간에는 30일 예측이 필요하다.");
        }
        long p30 = Math.max(a30, p14);
        if (n <= 30) {
            return p14 + roundedDivide(Math.multiplyExact(p30 - p14, n - 14), 16);
        }
        return roundedDivide(Math.multiplyExact(p30, n), 30);
    }

    private static Total total(Collection<AccountProjection> accounts) {
        List<String> methods = new ArrayList<>();
        boolean insufficient = false;
        long amount = 0;
        long daily = 0;
        LivingCostEstimator.Confidence lowest = LivingCostEstimator.Confidence.HIGH;
        boolean seed = false;
        for (AccountProjection account : accounts) {
            if (account.projectedAmount() == null) {
                insufficient = true;
                continue;
            }
            methods.add(account.method());
            amount = Math.addExact(amount, account.projectedAmount());
            daily = Math.addExact(daily, account.dailyAmount());
            if (account.confidence().ordinal() > lowest.ordinal()) {
                lowest = account.confidence();
            }
            seed |= account.dataOrigin() == LivingCostEstimator.DataOrigin.SEED;
        }
        String method = methods.isEmpty() ? null
                : methods.stream().distinct().count() == 1 ? methods.get(0) : METHOD_MIXED;
        if (insufficient) {
            // 일부 계좌만 더한 값을 주지 않는다(지난 3개월 실적 요약과 같은 규칙).
            return new Total(null, null, method, LivingCostEstimator.Confidence.INSUFFICIENT,
                    LivingCostEstimator.DataOrigin.INSUFFICIENT);
        }
        return new Total(amount, daily, method, lowest,
                seed ? LivingCostEstimator.DataOrigin.SEED : LivingCostEstimator.DataOrigin.REAL);
    }

    private static long round(double value) {
        return (long) Math.floor(value + 0.5);
    }

    /** 0 이상인 값을 나눠 원 단위로 반올림한다. */
    private static long roundedDivide(long dividend, long divisor) {
        return Math.addExact(Math.multiplyExact(dividend, 2), divisor) / Math.multiplyExact(divisor, 2);
    }

    /**
     * @param daily       어제까지 90일 일별 체크카드 생활 소비(오래된 날부터, 기록 시작 전은 0원)
     * @param historyDays 기록 시작부터 어제까지 일수(최대 90). 기록이 없으면 0
     * @param spentToday  오늘 이미 쓴 금액
     * @param seed        90일 안에 모의 소비 줄이 있는지
     */
    private record History(long[] daily, int historyDays, boolean hasSpending, long spentToday, boolean seed) {}

    /**
     * 계좌 하나의 예상 생활비. 기록이 없으면 금액은 null이고 confidence·dataOrigin은 INSUFFICIENT다.
     *
     * @param projectedAmount 예보 기간(내일부터 N일) 예상 생활비 = dailyAmount × N
     * @param dailyAmount     잔고예보 보수적 선에서 날마다 빼는 값
     * @param method          ML_V1(AI) 또는 TSB
     */
    record AccountProjection(Long accountId, Long projectedAmount, Long dailyAmount, String method,
            LivingCostEstimator.Confidence confidence, LivingCostEstimator.DataOrigin dataOrigin) {
        static AccountProjection insufficient(Long accountId) {
            return new AccountProjection(accountId, null, null, null, LivingCostEstimator.Confidence.INSUFFICIENT,
                    LivingCostEstimator.DataOrigin.INSUFFICIENT);
        }
    }

    /**
     * 통합 예상 생활비. 한 계좌라도 자료가 없으면 금액은 null이다.
     *
     * @param method 계좌 방식이 모두 같으면 그 값, 섞이면 MIXED. 자료 있는 계좌가 없으면 null
     */
    record Total(Long projectedAmount, Long dailyAmount, String method, LivingCostEstimator.Confidence confidence,
            LivingCostEstimator.DataOrigin dataOrigin) {}

    /** 계좌별 예상 생활비(입력 계좌 순서)와 통합 값. */
    record Projection(Map<Long, AccountProjection> byAccount, Total total) {}
}
