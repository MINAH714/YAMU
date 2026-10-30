package com.yamu.be.forecast;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 날짜 선 하나(통합 또는 계좌)에서 안전 잔액 미달을 찾는다. DB·외부 호출 없이 계산한다.
 *
 * <p>날마다 위험 수준을 선 기준으로 정한다. 확정 선이 기준 미만이면 DANGER, 예상·보수적 선만 미만이면 WARNING,
 * 그 밖은 SAFE다. 기준과 같은 금액은 미달이 아니다(FR-043 "임계값 아래").
 * 같은 위험 수준이 이어지는 날짜를 한 구간으로 묶고, WARNING과 DANGER가 바뀌면 구간을 나눈다.
 */
final class RiskDetector {

    private RiskDetector() {
    }

    /**
     * 선의 위험 수준·구간과 가장 나쁜 선의 최저·마지막 잔액을 돌려준다.
     * 가장 나쁜 선은 보수적 선이고, 생활비 추정이 없으면 예상 선이다.
     *
     * @param points    날짜순 매일 점(비어 있으면 안 된다)
     * @param threshold 안전 잔액(원)
     * @return 판단 결과
     */
    static Assessment assess(List<ForecastCalculator.BalancePoint> points, long threshold) {
        if (points.isEmpty()) {
            throw new IllegalArgumentException("판단할 날짜가 없다.");
        }
        Level worst = Level.SAFE;
        List<RiskWindow> windows = new ArrayList<>();
        int start = -1;
        for (int i = 0; i <= points.size(); i++) {
            Level level = i < points.size() ? levelOf(points.get(i), threshold) : Level.SAFE;
            if (start >= 0 && level != levelOf(points.get(start), threshold)) {
                windows.add(window(points.subList(start, i), threshold));
                start = -1;
            }
            if (start < 0 && level != Level.SAFE) {
                start = i;
            }
            if (level.ordinal() > worst.ordinal()) {
                worst = level;
            }
        }

        ForecastCalculator.BalancePoint lowest = points.get(0);
        for (ForecastCalculator.BalancePoint point : points) {
            if (worstBalance(point) < worstBalance(lowest)) {
                lowest = point;
            }
        }
        return new Assessment(worst, List.copyOf(windows), worstBalance(lowest), lowest.date(),
                worstBalance(points.get(points.size() - 1)));
    }

    private static Level levelOf(ForecastCalculator.BalancePoint point, long threshold) {
        if (point.confirmed() < threshold) {
            return Level.DANGER;
        }
        if (point.expected() < threshold || (point.conservative() != null && point.conservative() < threshold)) {
            return Level.WARNING;
        }
        return Level.SAFE;
    }

    /** 같은 위험 수준이 이어지는 날짜들을 구간 하나로 만든다. */
    private static RiskWindow window(List<ForecastCalculator.BalancePoint> days, long threshold) {
        Level level = levelOf(days.get(0), threshold);
        Line line;
        if (level == Level.DANGER) {
            line = Line.CONFIRMED;
        } else {
            line = days.stream().anyMatch(day -> day.expected() < threshold) ? Line.EXPECTED : Line.CONSERVATIVE;
        }
        ForecastCalculator.BalancePoint lowest = days.get(0);
        for (ForecastCalculator.BalancePoint day : days) {
            if (balanceOn(day, line) < balanceOn(lowest, line)) {
                lowest = day;
            }
        }
        long lowestBalance = balanceOn(lowest, line);

        List<Cause> causes = new ArrayList<>();
        for (ForecastCalculator.BalancePoint day : days) {
            for (FlowEvent event : day.events()) {
                if (event.amount() < 0) {
                    causes.add(new Cause(CauseType.valueOf(event.type().name()), event.title(), event.amount(),
                            event.refId(), day.date()));
                }
            }
        }
        // 예상 선은 기준 이상인데 생활비 추정을 뺀 보수적 선만 미만인 날이 있으면, 추정 생활비도 원인으로 알린다.
        boolean livingCostOnly = days.stream().anyMatch(day -> day.expected() >= threshold
                && day.conservative() != null && day.conservative() < threshold);
        if (livingCostOnly && lowest.conservative() != null) {
            causes.add(new Cause(CauseType.LIVING_COST_ESTIMATE, "추정 생활비",
                    Math.subtractExact(lowest.conservative(), lowest.expected()), null, lowest.date()));
        }
        return new RiskWindow(days.get(0).date(), days.get(days.size() - 1).date(), level, line, lowestBalance,
                Math.subtractExact(threshold, lowestBalance), List.copyOf(causes));
    }

    private static long balanceOn(ForecastCalculator.BalancePoint point, Line line) {
        return switch (line) {
            case CONFIRMED -> point.confirmed();
            case EXPECTED -> point.expected();
            case CONSERVATIVE -> point.conservative();
        };
    }

    private static long worstBalance(ForecastCalculator.BalancePoint point) {
        return point.conservative() != null ? point.conservative() : point.expected();
    }

    enum Level { SAFE, WARNING, DANGER }

    enum Line { CONFIRMED, EXPECTED, CONSERVATIVE }

    /** 잔액을 떨어뜨린 원인. 입출금 종류에 추정 생활비를 더했다. */
    enum CauseType { SCHEDULED_INCOME, SCHEDULED_OUTFLOW, CARD_BILLING, LIVING_COST_ESTIMATE }

    /**
     * @param amount 출금(음수, 원). 추정 생활비는 구간 최저 날까지 누적한 금액
     * @param refId  원본 참조(예정 항목·청구서 ID). 추정 생활비는 null
     * @param date   반영한 날짜. 추정 생활비는 구간 최저 날
     */
    record Cause(CauseType type, String title, long amount, Long refId, LocalDate date) {}

    /**
     * 같은 위험 수준이 이어지는 날짜 구간.
     *
     * @param line          구간에서 기준 미만인 가장 확실한 선(DANGER는 확정, WARNING은 예상이 있으면 예상, 없으면 보수적)
     * @param lowestBalance 그 선의 구간 최저 잔액(원)
     * @param shortfall     안전 잔액까지 모자라는 금액(원)
     */
    record RiskWindow(LocalDate from, LocalDate to, Level level, Line line, long lowestBalance, long shortfall,
            List<Cause> causes) {}

    /**
     * @param status         구간 중 가장 나쁜 위험 수준(없으면 SAFE)
     * @param minBalance     가장 나쁜 선의 최저 잔액(원)
     * @param minBalanceDate 최저 잔액 날짜(같으면 이른 날)
     * @param endBalance     가장 나쁜 선의 마지막 날 잔액(원)
     */
    record Assessment(Level status, List<RiskWindow> windows, long minBalance, LocalDate minBalanceDate,
            long endBalance) {}
}
