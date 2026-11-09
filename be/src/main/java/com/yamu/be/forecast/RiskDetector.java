package com.yamu.be.forecast;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 날짜 선 하나(통합 또는 계좌)에서 안전 잔액 미달을 찾는다. DB·외부 호출 없이 계산한다.
 *
 * <p>날마다 기준 미만인 가장 확실한 선(확정 > 예상 > 보수적)을 찾는다. 확정 선이면 DANGER, 예상·보수적 선이면 WARNING,
 * 미만인 선이 없으면 SAFE다. 기준과 같은 금액은 미달이 아니다(FR-043 "임계값 아래").
 * 그 선이 같은 날짜가 이어지면 한 구간으로 묶는다. 위험 수준이나 선이 바뀌면 구간을 나눠, 구간마다 부족액·최저일·원인이
 * 한 선을 기준으로 맞게 한다.
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
        Line startLine = null;
        for (int i = 0; i <= points.size(); i++) {
            // 마지막 다음 칸은 SAFE(null)로 보고 열린 구간을 닫는다.
            Line line = i < points.size() ? lineBelow(points.get(i), threshold) : null;
            if (start >= 0 && line != startLine) {
                windows.add(window(points.subList(start, i), startLine, threshold));
                start = -1;
            }
            if (start < 0 && line != null) {
                start = i;
                startLine = line;
            }
            Level level = levelOf(line);
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

    /**
     * 화면에 표시할 최종 수준. 통합 수준과 계좌별 수준 중 가장 나쁜 것이라, 통합이 안전해도 위험한 계좌가 있으면 그 수준이다.
     *
     * @param aggregate 통합 선 판단
     * @param accounts  선택 계좌별 판단
     * @return 최종 위험 수준
     */
    static Level overall(Assessment aggregate, Iterable<Assessment> accounts) {
        Level status = aggregate.status();
        for (Assessment account : accounts) {
            if (account.status().ordinal() > status.ordinal()) {
                status = account.status();
            }
        }
        return status;
    }

    /** 그날 기준 미만인 가장 확실한 선. 없으면 null(SAFE)이다. */
    private static Line lineBelow(ForecastCalculator.BalancePoint point, long threshold) {
        if (point.confirmed() < threshold) {
            return Line.CONFIRMED;
        }
        if (point.expected() < threshold) {
            return Line.EXPECTED;
        }
        if (point.conservative() != null && point.conservative() < threshold) {
            return Line.CONSERVATIVE;
        }
        return null;
    }

    private static Level levelOf(Line line) {
        if (line == null) {
            return Level.SAFE;
        }
        return line == Line.CONFIRMED ? Level.DANGER : Level.WARNING;
    }

    /**
     * 같은 선이 기준 미만인 날짜들을 구간 하나로 만든다. 원인은 그 선에 반영된 출금만 넣는다.
     * 확정 선은 확정 출금만, 예상 선은 확정·예상 출금을, 보수적 선은 거기에 마감 전 신용카드 사용 추정과 추정 생활비를 더한다.
     */
    private static RiskWindow window(List<ForecastCalculator.BalancePoint> days, Line line, long threshold) {
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
                // 그 선에 반영되지 않은 출금은 원인에 넣지 않는다(확정 선의 진행 중 청구, 예상 선의 신용카드 사용 추정 등).
                if (event.amount() < 0 && onLine(event.confidence(), line)) {
                    causes.add(new Cause(CauseType.valueOf(event.type().name()), event.title(), event.amount(),
                            event.refId(), day.date()));
                }
            }
        }
        // 보수적 선 구간은 예상 선은 기준 이상인데 추정 생활비(와 신용카드 사용 추정)를 빼서 미만이 된 날들이다.
        // 신용카드 사용 추정은 위에서 출금으로 넣었으므로, 여기서는 최저일까지 뺀 추정 생활비만 더한다.
        // 추정 생활비가 0원(신용카드 사용 추정만으로 미달)이면 원인이 아니라 넣지 않는다(Codex 검토 반영).
        if (line == Line.CONSERVATIVE && lowest.livingCost() != 0) {
            causes.add(new Cause(CauseType.LIVING_COST_ESTIMATE, "추정 생활비",
                    Math.negateExact(lowest.livingCost()), null, lowest.date()));
        }
        return new RiskWindow(days.get(0).date(), days.get(days.size() - 1).date(), levelOf(line), line,
                lowestBalance, Math.subtractExact(threshold, lowestBalance), List.copyOf(causes));
    }

    /** 그 등급의 입출금이 그 선에 반영되는지. 확정 선 ⊂ 예상 선 ⊂ 보수적 선이다. */
    private static boolean onLine(FlowEvent.Confidence confidence, Line line) {
        return switch (line) {
            case CONFIRMED -> confidence == FlowEvent.Confidence.CONFIRMED;
            case EXPECTED -> confidence != FlowEvent.Confidence.CONSERVATIVE;
            case CONSERVATIVE -> true;
        };
    }

    private static long balanceOn(ForecastCalculator.BalancePoint point, Line line) {
        return switch (line) {
            case CONFIRMED -> point.confirmed();
            case EXPECTED -> point.expected();
            case CONSERVATIVE -> point.conservative();
        };
    }

    /** 가장 나쁜 선(보수적, 없으면 예상)의 잔액. 최저·마지막 잔액과 가상 구매 비교 선에 쓴다. */
    static long worstBalance(ForecastCalculator.BalancePoint point) {
        return point.conservative() != null ? point.conservative() : point.expected();
    }

    enum Level { SAFE, WARNING, DANGER }

    enum Line { CONFIRMED, EXPECTED, CONSERVATIVE }

    /** 잔액을 떨어뜨린 원인. 입출금 종류({@link FlowEvent.Type})에 추정 생활비를 더했다. 이름이 같아야 한다. */
    enum CauseType {
        SCHEDULED_INCOME, SCHEDULED_OUTFLOW, CARD_BILLING, SIMULATED_PURCHASE, CARD_SPENDING_ESTIMATE, LIVING_COST_ESTIMATE
    }

    /**
     * @param amount 출금(음수, 원). 추정 생활비는 구간 최저 날까지 누적한 금액
     * @param refId  원본 참조(예정 항목·청구서 ID). 추정 생활비는 null
     * @param date   반영한 날짜. 추정 생활비는 구간 최저 날
     */
    record Cause(CauseType type, String title, long amount, Long refId, LocalDate date) {}

    /**
     * 같은 선이 기준 미만으로 이어지는 날짜 구간.
     *
     * @param line          날마다 기준 미만인 가장 확실한 선. 구간 안에서 같다(확정이면 DANGER, 예상·보수적이면 WARNING)
     * @param lowestBalance 그 선의 구간 최저 잔액(원)
     * @param shortfall     안전 잔액까지 모자라는 금액(원)
     * @param causes        그 선에 반영된 구간 안 출금. 보수적 선 구간은 최저일까지 누적한 추정 생활비를 더한다
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
