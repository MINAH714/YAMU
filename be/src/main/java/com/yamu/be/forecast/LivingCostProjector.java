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
 *   <li>방식: 기록이 {@value #AI_MIN_HISTORY_DAYS}일 이상이고 90일 안에 사용이 있으면 AI(7·14·30일 모델), 아니면 TSB.
 *       필요한 기간만 7 → 14 → 30일 순서로 부르고, AI 서버가 없거나 한 번이라도 실패·계약 위반이면 그 요청의 모든 계좌를
 *       TSB로 계산한다.</li>
 *   <li>기간 n일 값: (0, 0)·(7, AI 7일)·(14, AI 14일)·(30, AI 30일)을 직선으로 잇고 앞 점보다 낮은 점은 올린다.
 *       30일보다 길면 30일 값의 하루 평균 × n이다. TSB만 쓰는 계좌는 TSB 하루 예측 × n이다.
 *       기록 {@value #HIGH_CONFIDENCE_DAYS}일 미만인 AI 계좌는 30일 점을 max(AI 30일, TSB 30일)로 쓴다.</li>
 *   <li>잔고예보는 내일부터 N일 동안 생활비를 빼는데, 어제까지의 기록으로 한 예측은 오늘부터다. 그래서 오늘을 포함한 N+1일을
 *       예측하고, 오늘 이미 쓴 금액을 오늘 몫(N+1일 예측 ÷ (N+1))까지만 뺀 뒤 N으로 나눠 하루 값을 만든다. 예상 생활비는
 *       하루 값 × N이다(잔고예보 선과 같은 값).</li>
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
        return project(accountIds, days, today, horizonDays, AiLivingCostClient.Endpoint.LIVING_COST);
    }

    /**
     * {@link #project(List, List, LocalDate, int)}와 같은 규칙으로, 부를 AI API를 고른다. 신용카드 마감 전 사용 추정은 카드 번호를
     * 계좌 자리에, 신용 생활 소비를 일별 소비 자리에 넣고 {@code CREDIT_SPENDING}을 부른다(S15P31E103-405, 2026-11-10 결정:
     * 체크 생활비와 같은 규칙).
     *
     * @param accountIds 결과 순서. 신용이면 카드 번호
     * @param endpoint   부를 AI API
     */
    Projection project(List<Long> accountIds, List<LivingCostReader.DailySpending> days, LocalDate today,
            int horizonDays, AiLivingCostClient.Endpoint endpoint) {
        LocalDate asOf = today.minusDays(1);
        LocalDate windowStart = asOf.minusDays(HISTORY_DAYS - 1);
        int predictDays = horizonDays + 1;

        Map<Long, History> histories = new LinkedHashMap<>();
        for (Long accountId : accountIds) {
            histories.put(accountId, history(accountId, days, windowStart, asOf, today));
        }

        Map<String, Long> keys = new LinkedHashMap<>();
        Map<String, long[]> aiInputs = new LinkedHashMap<>();
        if (horizonDays > 0) {
            histories.forEach((accountId, history) -> {
                if (history.historyDays() >= AI_MIN_HISTORY_DAYS && history.hasSpending()) {
                    // 계좌번호·사용자 ID 대신 요청 안에서만 쓰는 순번을 보낸다.
                    String key = "a" + keys.size();
                    keys.put(key, accountId);
                    aiInputs.put(key, history.daily());
                }
            });
        }
        AiPoints aiPoints = aiPoints(endpoint, asOf, predictDays, keys, aiInputs);

        Map<Long, AccountProjection> byAccount = new LinkedHashMap<>();
        histories.forEach((accountId, history) -> byAccount.put(accountId,
                account(accountId, history, aiPoints.byAccount().get(accountId), predictDays, horizonDays)));
        return new Projection(byAccount, total(byAccount.values()), aiPoints.modelVersion());
    }

    /**
     * AI 예측을 계좌별 [7일, 14일, 30일]로 돌려준다. n일 값에 필요한 기간만 7 → 14 → 30일 순서로 부르고(7일 이하면 7일만,
     * 14일 이하면 7·14일), 부르지 않은 칸은 -1이다. 하나라도 실패하면 남은 기간은 부르지 않고 빈 값(모두 TSB)을 돌려준다.
     * 모델 버전은 부른 기간이 모두 같으면 그 값, 다르면 "7:버전,14:버전"처럼 기간과 함께 남긴다(AI 서버는 기간끼리 버전을 검사하지 않음).
     */
    private AiPoints aiPoints(AiLivingCostClient.Endpoint endpoint, LocalDate asOf, int predictDays,
            Map<String, Long> keys, Map<String, long[]> inputs) {
        if (inputs.isEmpty() || !ai.enabled()) {
            return AiPoints.NONE;
        }
        int[] horizons = predictDays <= 7 ? new int[] {7} : predictDays <= 14 ? new int[] {7, 14} : new int[] {7, 14, 30};
        List<AiLivingCostClient.Answer> answers = new ArrayList<>();
        for (int horizon : horizons) {
            Optional<AiLivingCostClient.Answer> answer = ai.predict(endpoint, asOf, horizon, inputs);
            if (answer.isEmpty()) {
                return AiPoints.NONE;
            }
            answers.add(answer.get());
        }
        Map<Long, long[]> out = new HashMap<>();
        for (Map.Entry<String, Long> key : keys.entrySet()) {
            long[] point = {-1, -1, -1};
            for (int i = 0; i < answers.size(); i++) {
                point[i] = answers.get(i).amounts().get(key.getKey());
            }
            out.put(key.getValue(), point);
        }
        List<String> versions = answers.stream().map(AiLivingCostClient.Answer::modelVersion).distinct().toList();
        String version = versions.size() == 1 ? versions.get(0) : java.util.stream.IntStream.range(0, answers.size())
                .mapToObj(i -> horizons[i] + ":" + answers.get(i).modelVersion())
                .collect(java.util.stream.Collectors.joining(","));
        return new AiPoints(out, version);
    }

    /** AI 예측 점과 그 모델 버전. AI를 쓰지 않았거나 실패하면 비어 있고 버전은 null이다. */
    private record AiPoints(Map<Long, long[]> byAccount, String modelVersion) {
        static final AiPoints NONE = new AiPoints(Map.of(), null);
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
        long upcoming;
        if (aiPoint == null) {
            upcoming = round(tsbDaily * predictDays);
        } else {
            long a30 = aiPoint[2];
            if (a30 >= 0 && history.historyDays() < HIGH_CONFIDENCE_DAYS) {
                // 기록 14~59일은 AI 30일 예측이 생활비를 적게 보는 쪽으로 치우친다(기록 30일에서 −13.5%·−14.3%).
                // 그래서 TSB 30일보다 낮으면 TSB 30일을 쓴다. AI가 더 크게 보면 AI 그대로다(!148 리뷰, 2026-11-06 결정)
                a30 = Math.max(a30, round(tsbDaily * 30));
            }
            upcoming = interpolate(aiPoint[0], aiPoint[1], a30, predictDays);
        }
        // 오늘 이미 쓴 돈은 오늘 몫(N+1일 예측 ÷ (N+1))까지만 뺀다. 오늘 크게 써도 내일부터의 생활비가 줄지 않게 한다(!136 리뷰)
        long todayShare = roundedDivide(upcoming, predictDays);
        long daily = horizonDays == 0 ? 0
                : roundedDivide(upcoming - Math.min(history.spentToday(), todayShare), horizonDays);
        LivingCostEstimator.Confidence confidence = history.historyDays() >= HIGH_CONFIDENCE_DAYS
                ? LivingCostEstimator.Confidence.HIGH
                : history.historyDays() >= AI_MIN_HISTORY_DAYS ? LivingCostEstimator.Confidence.MEDIUM
                : LivingCostEstimator.Confidence.LOW;
        return new AccountProjection(accountId, Math.multiplyExact(daily, horizonDays), daily, method, confidence,
                history.seed() ? LivingCostEstimator.DataOrigin.SEED : LivingCostEstimator.DataOrigin.REAL);
    }

    /**
     * (0, 0)·(7, a7)·(14, a14)·(30, a30)을 직선으로 이은 n일 값. 앞 점보다 낮은 점은 앞 점 높이로 올려 기간을 늘려도 줄지 않게 한다.
     * 30일보다 길면 30일 값의 하루 평균 × n. n이 7 이하이면 a14·a30을, 14 이하이면 a30을 쓰지 않는다(-1이어도 된다).
     */
    static long interpolate(long a7, long a14, long a30, int n) {
        if (n < 1) {
            throw new IllegalArgumentException("기간은 1일 이상이어야 한다.");
        }
        if (n <= 7) {
            return roundedDivide(Math.multiplyExact(a7, n), 7);
        }
        if (a14 < 0) {
            throw new IllegalArgumentException("7일보다 긴 기간에는 14일 예측이 필요하다.");
        }
        long p14 = Math.max(a14, a7);
        if (n <= 14) {
            return a7 + roundedDivide(Math.multiplyExact(p14 - a7, n - 7), 7);
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

    /**
     * 계좌별 예상 생활비(입력 계좌 순서)와 통합 값.
     *
     * @param aiModelVersion 이번 계산에서 AI(ML_V1) 계좌에 쓴 모델 버전. AI를 부르지 않았거나 실패해 모두 TSB면 null(-405)
     */
    record Projection(Map<Long, AccountProjection> byAccount, Total total, String aiModelVersion) {}
}
