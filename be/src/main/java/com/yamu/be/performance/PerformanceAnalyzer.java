package com.yamu.be.performance;

import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 실적 유불리 판정 계산(S15P31E103-375, 2026-11-10 결정). DB·외부 호출 없이 입력만으로 계산한다.
 *
 * <ol>
 *   <li>월말 예상 = 지금 인정 실적 + 이 카드로 낼 남은 예정 지출 + 자연 소비(+ what-if로 옮긴다고 가정한 예정 지출).</li>
 *   <li>자연 소비 = 최근 완료 3개월(데이터가 있는 달만)의 같은 기간(내일~말일) 인정 실적 평균.</li>
 *   <li>인정 ≥ 목표면 {@link PerformanceGuide#ALREADY_ACHIEVED}, 예상 ≥ 목표면 {@link PerformanceGuide#KEEP_NATURAL}.</li>
 *   <li>옮길 수 있는 예정 지출(eligible)로 모자라는 돈을 다 채우면 추가 지출 0원이라 {@link PerformanceGuide#ACHIEVE_RECOMMENDED}.</li>
 *   <li>그래도 모자라는 돈 ≤ 더 받는 혜택이면 {@link PerformanceGuide#ACHIEVE_RECOMMENDED}, 크거나 혜택을 금액으로 셀 수 없으면
 *       {@link PerformanceGuide#EXTRA_SPENDING_NOT_RECOMMENDED}(lossIfForced = 모자라는 돈 − 혜택).</li>
 * </ol>
 *
 * <p>더 받는 혜택 = 목표 구간의 원화 월 한도 합 − 예상 실적이 닿는 (목표보다 낮은) 구간의 원화 월 한도 합. 닿는 구간이 없으면
 * 실적 0원일 때의 한도(실적 조건 없는 혜택)를 뺀다. 한도까지 다 받는다고 본 최대치라 실제보다 클 수 있다(추정).
 */
final class PerformanceAnalyzer {

    /** 예상이 목표의 이 비율 이상이면 BORDERLINE. API 명세서 「실적 달성 유불리 분석」. */
    private static final long BORDERLINE_PERCENT = 90;

    private PerformanceAnalyzer() {
    }

    /** 실적 구간. monthlyKrwLimit은 그 구간에서 받는 원화 월 한도 합(포인트·횟수뿐이면 null). */
    record Tier(long threshold, Long monthlyKrwLimit, List<String> benefitNames) {
    }

    /**
     * 이번 달 목표 실적과 카드의 실적 구간.
     *
     * @param baseMonthlyKrwLimit 이번 달 실적이 가장 낮은 구간에도 못 미칠 때 다음 달에 받는 원화 월 한도 합(실적 조건 없는
     *                            혜택). 그런 혜택이 없으면 0, 모르면 null
     */
    record Target(long amount, List<Tier> tiers, Long baseMonthlyKrwLimit) {
    }

    /** 지난 달 하나의 같은 기간 인정 실적 합. */
    record MonthSample(YearMonth month, long amount) {
    }

    /** 이 카드로 낼 남은 예정 지출(실적 인정 대상만). */
    record Scheduled(long cashflowItemId, String title, LocalDate date, long amount) {
    }

    /** 이 카드로 옮길 수 있는지 판정한 예정 지출. */
    record Movable(long cashflowItemId, String title, LocalDate date, long amount, String currentMethod,
            boolean eligible, LocalDate deadline, String note, String reason) {
    }

    record Input(LocalDate today, Target target, long recognized, List<MonthSample> history,
            List<Scheduled> scheduled, List<Movable> movable, Set<Long> scenarioItemIds) {
    }

    record Step(int no, String label, Long amount, String note) {
    }

    enum Likelihood { LIKELY, BORDERLINE, UNLIKELY }

    record Result(PerformanceGuide guide, String headline, String message, long remaining, long natural,
            long averageDaily, long scheduledTotal, long scenarioMovedTotal, long projected, Likelihood likelihood,
            long shortfallIfTrend, long eligibleMovableTotal, Long shortfall, Long extraBenefit, Long lossIfForced,
            List<String> benefitsAtStake, List<Step> steps, List<Movable> movable, List<Movable> scenarioMoved) {
    }

    static Result analyze(Input in) {
        long target = in.target().amount();
        long remaining = Math.max(0, target - in.recognized());
        Tier targetTier = in.target().tiers().stream().filter(t -> t.threshold() == target).findFirst().orElse(null);
        List<String> atStake = targetTier == null ? List.of() : targetTier.benefitNames();

        if (in.recognized() >= target) {
            return new Result(PerformanceGuide.ALREADY_ACHIEVED, "이번 달 실적을 채웠어요",
                    "다음 달에 " + label(target) + " 구간 혜택을 받아요", 0, 0, 0, 0, 0, in.recognized(),
                    Likelihood.LIKELY, 0, 0, 0L, null, null, atStake, List.of(), List.of(), List.of());
        }

        int daysLeft = in.today().lengthOfMonth() - in.today().getDayOfMonth();
        long natural = daysLeft == 0 || in.history().isEmpty() ? 0 : Math.round(
                in.history().stream().mapToLong(MonthSample::amount).average().orElse(0));
        long averageDaily = daysLeft == 0 ? 0 : Math.round((double) natural / daysLeft);
        long scheduledTotal = in.scheduled().stream().mapToLong(Scheduled::amount).sum();

        List<Movable> moved = in.movable().stream()
                .filter(m -> m.eligible() && in.scenarioItemIds().contains(m.cashflowItemId())).toList();
        List<Movable> stillMovable = in.movable().stream().filter(m -> !moved.contains(m)).toList();
        long movedTotal = moved.stream().mapToLong(Movable::amount).sum();

        long projected = in.recognized() + scheduledTotal + natural + movedTotal;
        long shortfallIfTrend = Math.max(0, target - projected);
        long eligibleTotal = stillMovable.stream().filter(Movable::eligible).mapToLong(Movable::amount).sum();
        long shortfall = Math.max(0, shortfallIfTrend - eligibleTotal);
        Long extra = extraBenefit(in.target(), targetTier, projected);

        PerformanceGuide guide;
        Long loss = null;
        List<Movable> needed = List.of();
        if (shortfallIfTrend == 0) {
            guide = PerformanceGuide.KEEP_NATURAL;
        } else if (shortfall == 0) {
            guide = PerformanceGuide.ACHIEVE_RECOMMENDED;
            needed = needed(stillMovable, shortfallIfTrend);
            loss = 0L;
        } else if (extra != null && shortfall <= extra) {
            guide = PerformanceGuide.ACHIEVE_RECOMMENDED;
            loss = 0L;
        } else {
            guide = PerformanceGuide.EXTRA_SPENDING_NOT_RECOMMENDED;
            loss = extra == null ? null : shortfall - extra;
        }

        int nextMonth = in.today().plusMonths(1).getMonthValue();
        List<Step> steps = new ArrayList<>();
        steps.add(new Step(1, "남은 실적", remaining, "기준 " + won(target) + " − 인정 " + won(in.recognized())));
        steps.add(new Step(2, "월말까지 자연 소비 예측", natural, naturalNote(in.history(), in.today(), daysLeft)));
        steps.add(new Step(3, "이 카드로 낼 예정 지출", scheduledTotal + movedTotal,
                scheduledNote(in.scheduled().size(), moved.size())));
        steps.add(new Step(4, "예정 지출을 옮기면 채울 수 있는 돈", eligibleTotal,
                movableNote(stillMovable)));
        steps.add(new Step(5, "그래도 모자라는 돈", shortfall, shortfall > 0 ? "더 써야 채우는 돈" : null));
        steps.add(new Step(6, "달성하면 더 받는 혜택", extra, extra == null
                ? "원화 한도가 없거나 알 수 없어 금액으로 셀 수 없어요"
                : nextMonth + "월 " + (atStake.isEmpty() ? label(target) + " 구간 혜택" : String.join(", ", atStake))
                        + " 한도까지 받을 때"));
        steps.add(new Step(7, "판단", null, judgement(guide, shortfall, extra, needed)));

        return new Result(guide, headline(guide, shortfall, extra, needed), message(guide, projected, target,
                shortfall, extra, needed), remaining, natural, averageDaily, scheduledTotal, movedTotal, projected,
                likelihood(projected, target), shortfallIfTrend, eligibleTotal, shortfall, extra, loss, atStake,
                List.copyOf(steps), stillMovable, moved);
    }

    /**
     * 목표 구간의 원화 월 한도 합 − 예상 실적이 닿는 낮은 구간의 원화 월 한도 합. 예상이 어느 낮은 구간에도 닿지 않으면 실적 0원일
     * 때 받는 한도({@link Target#baseMonthlyKrwLimit}, 실적 조건 없는 혜택)를 뺀다(2026-11-12: 0을 빼면 그 혜택을 채워야 받는
     * 것처럼 센다). 목표 구간이나 빼야 할 한도를 원화로 셀 수 없으면(모르는 한도 포함) null(2026-11-11 결정: 0으로 보면 더 받는
     * 혜택을 실제보다 크게 잡는다).
     */
    static Long extraBenefit(Target target, Tier targetTier, long projected) {
        if (targetTier == null || targetTier.monthlyKrwLimit() == null) {
            return null;
        }
        Optional<Tier> reached = target.tiers().stream()
                .filter(t -> t.threshold() < target.amount() && t.threshold() <= projected)
                .max(Comparator.comparingLong(Tier::threshold));
        // Optional.map은 null 한도를 "닿은 구간 없음"으로 바꾸므로 쓰지 않는다
        Long base = reached.isPresent() ? reached.get().monthlyKrwLimit() : target.baseMonthlyKrwLimit();
        if (base == null) {
            return null;
        }
        return Math.max(0, targetTier.monthlyKrwLimit() - base);
    }

    /** 모자라는 돈을 채울 때까지 금액 큰 순으로 고른 옮길 예정 지출. */
    private static List<Movable> needed(List<Movable> movable, long shortfall) {
        List<Movable> picked = new ArrayList<>();
        long sum = 0;
        for (Movable m : movable) {
            if (!m.eligible()) {
                continue;
            }
            picked.add(m);
            sum += m.amount();
            if (sum >= shortfall) {
                break;
            }
        }
        return picked;
    }

    static Likelihood likelihood(long projected, long target) {
        if (projected >= target) {
            return Likelihood.LIKELY;
        }
        return projected * 100 >= target * BORDERLINE_PERCENT ? Likelihood.BORDERLINE : Likelihood.UNLIKELY;
    }

    private static String headline(PerformanceGuide guide, long shortfall, Long extra, List<Movable> needed) {
        return switch (guide) {
            case KEEP_NATURAL -> "평소대로 쓰면 이번 달 실적을 채워요";
            case ACHIEVE_RECOMMENDED -> needed.isEmpty()
                    ? won(shortfall) + " 더 쓰면 혜택을 " + won(extra) + " 더 받아요"
                    : movedTitle(needed) + " 결제 카드만 바꾸면 추가 지출 없이 채워요";
            case EXTRA_SPENDING_NOT_RECOMMENDED -> "이번 달은 채우지 않는 게 유리해요";
            case ALREADY_ACHIEVED -> "이번 달 실적을 채웠어요";
        };
    }

    private static String message(PerformanceGuide guide, long projected, long target, long shortfall, Long extra,
            List<Movable> needed) {
        return switch (guide) {
            case KEEP_NATURAL -> "월말 예상 실적이 " + won(projected) + "으로 기준 " + won(target) + "을 넘어요.";
            case ACHIEVE_RECOMMENDED -> needed.isEmpty()
                    ? "꼭 필요한 소비라면 이 카드로 결제해 보세요. 필요 없는 소비라면 채우지 않아도 괜찮아요."
                    : monthDay(earliestDeadline(needed)) + "까지 " + movedTitle(needed)
                            + " 결제처에서도 결제 카드를 바꿔 주세요.";
            case EXTRA_SPENDING_NOT_RECOMMENDED -> extra == null
                    ? "채우려면 " + won(shortfall) + " 더 써야 하고, 더 받는 혜택은 금액으로 셀 수 없어요. 억지로 채우지 않아도 괜찮아요."
                    : "채우려면 " + won(shortfall) + " 더 써야 하지만 더 받는 혜택은 많아야 " + won(extra)
                            + "이에요. 억지로 채우지 않아도 괜찮아요.";
            case ALREADY_ACHIEVED -> null;
        };
    }

    private static String judgement(PerformanceGuide guide, long shortfall, Long extra, List<Movable> needed) {
        return switch (guide) {
            case KEEP_NATURAL -> "평소 소비로 달성 → 자연 소비 유지";
            case ACHIEVE_RECOMMENDED -> needed.isEmpty()
                    ? "더 써야 하는 돈 " + won(shortfall) + " ≤ 더 받는 혜택 " + won(extra) + " → 실적 달성 추천"
                    : "추가 지출 0원으로 달성 → 실적 달성 추천";
            case EXTRA_SPENDING_NOT_RECOMMENDED -> extra == null
                    ? "더 받는 혜택을 셀 수 없음 → 추가 소비 비추천"
                    : "더 써야 하는 돈 " + won(shortfall) + " > 더 받는 혜택 " + won(extra) + " → 추가 소비 비추천";
            case ALREADY_ACHIEVED -> "이미 달성";
        };
    }

    private static LocalDate earliestDeadline(List<Movable> needed) {
        return needed.stream().map(Movable::deadline).min(Comparator.naturalOrder()).orElseThrow();
    }

    private static String monthDay(LocalDate date) {
        return date.getMonthValue() + "월 " + date.getDayOfMonth() + "일";
    }

    private static String movedTitle(List<Movable> needed) {
        return needed.size() == 1 ? needed.get(0).title() : "예정 지출 " + needed.size() + "건";
    }

    private static String naturalNote(List<MonthSample> history, LocalDate today, int daysLeft) {
        if (daysLeft == 0) {
            return "오늘이 말일이라 남은 날이 없어요";
        }
        String months = history.stream()
                .sorted(Comparator.comparing(MonthSample::month))
                .map(s -> s.month().getMonthValue() + "월 " + won(s.amount()))
                .collect(Collectors.joining(" · "));
        return "최근 " + history.size() + "개월 같은 기간(" + (today.getDayOfMonth() + 1) + "일~말일) 평균 · " + months;
    }

    private static String scheduledNote(int scheduled, int moved) {
        if (scheduled == 0 && moved == 0) {
            return "이 카드로 낼 예정 지출이 없어요";
        }
        String note = "잔고예보 예정 지출 " + scheduled + "건";
        return moved == 0 ? note : note + " + 옮긴다고 가정한 " + moved + "건";
    }

    private static String movableNote(Collection<Movable> movable) {
        long eligible = movable.stream().filter(Movable::eligible).count();
        if (movable.isEmpty()) {
            return "옮길 예정 지출이 없어요";
        }
        return "다른 결제 수단의 예정 지출 " + movable.size() + "건 중 " + eligible + "건";
    }

    /** "30만원 이상"처럼 만원 단위로. 실적 상세(S15P31E103-373)의 구간 이름과 같은 규칙. */
    static String label(long amount) {
        long man = amount / 10_000;
        long rest = amount % 10_000;
        NumberFormat format = NumberFormat.getIntegerInstance(Locale.KOREA);
        String text;
        if (man == 0) {
            text = format.format(rest) + "원";
        } else if (rest == 0) {
            text = format.format(man) + "만원";
        } else {
            text = format.format(man) + "만 " + format.format(rest) + "원";
        }
        return text + " 이상";
    }

    static String won(Long amount) {
        return NumberFormat.getIntegerInstance(Locale.KOREA).format(amount == null ? 0 : amount) + "원";
    }
}
