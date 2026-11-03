package com.yamu.be.forecast;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 계좌별 최근 완료 3개월의 생활 소비로 하루·한 달 평균 생활비를 추정한다(명세 AVG_3M). DB·외부 호출 없이 계산한다.
 *
 * <ul>
 *   <li>원천의 일별 줄을 월별로 더한다. 그달(이번 달은 오늘까지) 모든 날의 줄이 있고 모두 complete인 달만 complete다.</li>
 *   <li>이번 달은 빼고 지난 3개월 중 거래를 빠짐없이 확인한(complete) 달만 쓴다. 0원으로 확인된 달도 평균에 넣는다.</li>
 *   <li>한 계좌 안에서 실제 거래(REAL)와 모의 소비(SEED)를 섞지 않는다(Task 7.1.1 정책). REAL 달이 하나라도 있으면
 *       REAL 달만, 없을 때만 SEED 달을 쓴다.</li>
 *   <li>일평균 = 쓴 달들의 합 ÷ 그 달들의 실제 일수, 월평균 = 합 ÷ 달 수(모두 원 단위 반올림).</li>
 *   <li>통합 값은 계좌별 값의 합이다. 한 계좌라도 자료가 없으면 통합 평균은 비운다(일부만 더한 값을 주지 않는다).</li>
 * </ul>
 */
final class LivingCostEstimator {
    static final int MONTHS = 3;
    /** 이번 달 하루 평균이 평소의 1.3배를 넘으면 소비 속도가 빠르다고 본다(명세 paceCheck). */
    private static final BigDecimal PACE_LIMIT = new BigDecimal("1.3");

    private LivingCostEstimator() {
    }

    /**
     * 선택 계좌마다 생활비를 추정하고 통합 값을 더한다.
     *
     * @param accountIds 선택 계좌(결과 순서)
     * @param days       일별 생활 소비 합계. 선택하지 않은 계좌의 줄은 무시하고, 완료 3개월보다 앞선 날의 줄은
     *                   계약 위반(음수·중복 등)만 검사한 뒤 계산에 쓰지 않는다. 체크카드 몫은 검사만 하고 AVG_3M에는 쓰지 않는다
     * @param today      한국 날짜 오늘. 완료 3개월과 이번 달을 정한다
     * @return 계좌별·통합 추정
     */
    static Estimate estimate(List<Long> accountIds, List<LivingCostReader.DailySpending> days, LocalDate today) {
        YearMonth thisMonth = YearMonth.from(today);
        YearMonth firstMonth = thisMonth.minusMonths(MONTHS);
        Set<Long> selected = Set.copyOf(accountIds);
        Map<Long, List<LivingCostReader.DailySpending>> rowsByAccount = new LinkedHashMap<>();
        accountIds.forEach(accountId -> rowsByAccount.put(accountId, new ArrayList<>()));
        Set<String> seen = new HashSet<>();
        for (LivingCostReader.DailySpending row : days) {
            if (!selected.contains(row.accountId())) {
                continue;
            }
            // 예외 메시지는 전역 예외 로그에 남으므로 계좌·날짜·소비액을 넣지 않는다.
            if (row.amount() < 0) {
                throw new IllegalStateException("생활비 원천이 음수 소비 합계를 줬다.");
            }
            if (row.checkCardAmount() < 0 || row.checkCardAmount() > row.amount()) {
                throw new IllegalStateException("생활비 원천의 체크카드 몫이 0원보다 작거나 그날 합계보다 크다.");
            }
            if (row.date().isAfter(today)) {
                throw new IllegalStateException("생활비 원천이 오늘보다 뒤 날짜의 줄을 줬다.");
            }
            if (!seen.add(row.accountId() + ":" + row.date() + ":" + row.origin())) {
                throw new IllegalStateException("생활비 원천이 같은 계좌·날짜·출처의 줄을 두 번 줬다.");
            }
            rowsByAccount.get(row.accountId()).add(row);
        }

        Map<Long, AccountEstimate> byAccount = new LinkedHashMap<>();
        rowsByAccount.forEach((accountId, rows) -> byAccount.put(accountId,
                estimateAccount(accountId, monthly(rows, firstMonth, today), firstMonth, thisMonth, today)));
        return new Estimate(byAccount, total(byAccount.values()));
    }

    /**
     * 한 계좌의 일별 줄을 월·출처별 합계로 더한다. 그달(이번 달은 오늘까지)의 모든 날에 줄이 있고 모두 complete일 때만
     * 그 월을 complete로 본다. 완료 3개월보다 앞선 날은 버린다.
     */
    private static List<MonthlySpending> monthly(List<LivingCostReader.DailySpending> rows, YearMonth firstMonth,
            LocalDate today) {
        Map<YearMonth, Map<LivingCostReader.Origin, MonthTally>> tallies = new TreeMap<>();
        for (LivingCostReader.DailySpending row : rows) {
            YearMonth month = YearMonth.from(row.date());
            if (month.isBefore(firstMonth)) {
                continue;
            }
            tallies.computeIfAbsent(month, key -> new EnumMap<>(LivingCostReader.Origin.class))
                    .computeIfAbsent(row.origin(), key -> new MonthTally())
                    .add(row);
        }
        YearMonth thisMonth = YearMonth.from(today);
        List<MonthlySpending> months = new ArrayList<>();
        tallies.forEach((month, byOrigin) -> byOrigin.forEach((origin, tally) -> {
            int expectedDays = month.equals(thisMonth) ? today.getDayOfMonth() : month.lengthOfMonth();
            // 중복·미래 날짜는 위에서 거절했으므로 줄 수가 일수와 같으면 모든 날이 있다.
            boolean complete = tally.allComplete && tally.days == expectedDays;
            months.add(new MonthlySpending(month, tally.sum, origin, complete));
        }));
        return months;
    }

    private static AccountEstimate estimateAccount(Long accountId, List<MonthlySpending> rows,
            YearMonth firstMonth, YearMonth thisMonth, LocalDate today) {
        List<MonthlySpending> real = completedMonths(rows, LivingCostReader.Origin.REAL,
                firstMonth, thisMonth);
        LivingCostReader.Origin origin = real.isEmpty() ? LivingCostReader.Origin.SEED : LivingCostReader.Origin.REAL;
        List<MonthlySpending> used = real.isEmpty()
                ? completedMonths(rows, LivingCostReader.Origin.SEED, firstMonth, thisMonth) : real;
        if (used.isEmpty()) {
            return AccountEstimate.insufficient(accountId);
        }
        long sum = 0;
        long days = 0;
        List<MonthAmount> sources = new ArrayList<>();
        for (MonthlySpending row : used) {
            sum = Math.addExact(sum, row.amount());
            days += row.month().lengthOfMonth();
            sources.add(new MonthAmount(row.month(), row.amount()));
        }
        long daily = roundedDivide(sum, days);
        long monthly = roundedDivide(sum, used.size());
        PaceCheck pace = rows.stream()
                .filter(row -> row.month().equals(thisMonth) && row.origin() == origin && row.complete())
                .findFirst()
                .map(row -> pace(roundedDivide(row.amount(), today.getDayOfMonth()), daily))
                .orElse(null);
        return new AccountEstimate(accountId, daily, monthly, confidence(used.size()),
                origin == LivingCostReader.Origin.REAL ? DataOrigin.REAL : DataOrigin.SEED, List.copyOf(sources), pace);
    }

    /** 완료 3개월 안에서 거래를 다 확인한 그 출처의 달. 오래된 달부터. */
    private static List<MonthlySpending> completedMonths(List<MonthlySpending> rows,
            LivingCostReader.Origin origin, YearMonth firstMonth, YearMonth thisMonth) {
        return rows.stream()
                .filter(row -> row.origin() == origin && row.complete())
                .filter(row -> !row.month().isBefore(firstMonth) && row.month().isBefore(thisMonth))
                .sorted(Comparator.comparing(MonthlySpending::month))
                .toList();
    }

    private static Total total(Collection<AccountEstimate> accounts) {
        boolean insufficient = accounts.stream().anyMatch(account -> account.dataOrigin() == DataOrigin.INSUFFICIENT);
        if (insufficient) {
            return new Total(null, null, Confidence.INSUFFICIENT, DataOrigin.INSUFFICIENT, List.of(), null);
        }
        long daily = 0;
        long monthly = 0;
        Map<YearMonth, Long> byMonth = new TreeMap<>();
        Confidence lowest = Confidence.HIGH;
        boolean seed = false;
        boolean allPace = true;
        long thisMonthDaily = 0;
        for (AccountEstimate account : accounts) {
            daily = Math.addExact(daily, account.dailyAverage());
            monthly = Math.addExact(monthly, account.monthlyAverage());
            account.sources().forEach(source -> byMonth.merge(source.month(), source.amount(), Math::addExact));
            if (account.confidence().ordinal() > lowest.ordinal()) {
                lowest = account.confidence();
            }
            seed |= account.dataOrigin() == DataOrigin.SEED;
            if (account.pace() == null) {
                allPace = false;
            } else {
                thisMonthDaily = Math.addExact(thisMonthDaily, account.pace().thisMonthDaily());
            }
        }
        List<MonthAmount> sources = byMonth.entrySet().stream()
                .map(entry -> new MonthAmount(entry.getKey(), entry.getValue())).toList();
        // REAL 계좌와 SEED 계좌를 함께 고르면 통합 값에 모의 소비가 들어 있으므로 SEED로 알린다. 계좌 하나 안에서는 섞지 않는다.
        return new Total(daily, monthly, lowest, seed ? DataOrigin.SEED : DataOrigin.REAL, sources,
                allPace ? pace(thisMonthDaily, daily) : null);
    }

    /** 평소 하루 평균이 0원이면 비율을 정할 수 없어 계산하지 않는다. */
    private static PaceCheck pace(long thisMonthDaily, long averageDaily) {
        if (averageDaily == 0) {
            return null;
        }
        BigDecimal ratio = BigDecimal.valueOf(thisMonthDaily).divide(BigDecimal.valueOf(averageDaily), 4,
                RoundingMode.HALF_UP);
        boolean flagged = BigDecimal.valueOf(thisMonthDaily).compareTo(BigDecimal.valueOf(averageDaily).multiply(PACE_LIMIT)) > 0;
        return new PaceCheck(thisMonthDaily, averageDaily, ratio.subtract(BigDecimal.ONE).setScale(2, RoundingMode.HALF_UP),
                flagged);
    }

    private static Confidence confidence(int months) {
        return switch (months) {
            case 3 -> Confidence.HIGH;
            case 2 -> Confidence.MEDIUM;
            default -> Confidence.LOW;
        };
    }

    /** 0 이상인 값을 나눠 원 단위로 반올림한다. */
    private static long roundedDivide(long dividend, long divisor) {
        return Math.addExact(Math.multiplyExact(dividend, 2), divisor) / Math.multiplyExact(divisor, 2);
    }

    enum Confidence { HIGH, MEDIUM, LOW, INSUFFICIENT }

    enum DataOrigin { REAL, SEED, INSUFFICIENT }

    /** 일별 줄을 더한 한 계좌·한 달·한 출처의 합계. complete면 그달 모든 날을 빠짐없이 확인했다. */
    private record MonthlySpending(YearMonth month, long amount, LivingCostReader.Origin origin, boolean complete) {}

    private static final class MonthTally {
        private long sum;
        private int days;
        private boolean allComplete = true;

        private void add(LivingCostReader.DailySpending row) {
            sum = Math.addExact(sum, row.amount());
            days++;
            allComplete &= row.complete();
        }
    }

    /** 한 달의 생활 소비 합계(원). */
    record MonthAmount(YearMonth month, long amount) {}

    /**
     * 이번 달 소비 속도.
     *
     * @param thisMonthDaily 이번 달 오늘까지 하루 평균(원)
     * @param averageDaily   최근 3개월 하루 평균(원)
     * @param overRatio      평소보다 몇 배 더 썼는지에서 1을 뺀 값(0.3이면 30% 초과)
     * @param flagged        평소의 1.3배를 넘었는지
     */
    record PaceCheck(long thisMonthDaily, long averageDaily, BigDecimal overRatio, boolean flagged) {}

    /**
     * 계좌 하나의 추정. 자료가 없으면 평균은 null이고 confidence·dataOrigin은 INSUFFICIENT다.
     *
     * @param sources 평균에 쓴 달(오래된 달부터)
     * @param pace    이번 달 자료가 없거나 평소 평균이 0원이면 null
     */
    record AccountEstimate(Long accountId, Long dailyAverage, Long monthlyAverage, Confidence confidence,
            DataOrigin dataOrigin, List<MonthAmount> sources, PaceCheck pace) {
        static AccountEstimate insufficient(Long accountId) {
            return new AccountEstimate(accountId, null, null, Confidence.INSUFFICIENT, DataOrigin.INSUFFICIENT,
                    List.of(), null);
        }
    }

    /** 통합 추정. 한 계좌라도 자료가 없으면 평균은 null이다. */
    record Total(Long dailyAverage, Long monthlyAverage, Confidence confidence, DataOrigin dataOrigin,
            List<MonthAmount> sources, PaceCheck pace) {}

    /** 계좌별 추정(입력 계좌 순서)과 통합 추정. */
    record Estimate(Map<Long, AccountEstimate> byAccount, Total total) {}
}
