package com.yamu.be.forecast;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 계좌별 시작 잔액에 미래 입출금을 날짜순으로 더해 날짜별 예상 잔액을 계산한다. DB·외부 호출 없이 입력만으로 계산한다.
 * 확정 선은 CONFIRMED 입출금만, 예상 선은 CONFIRMED와 EXPECTED를 모두 더한다.
 * 보수적 선은 예상 선에 CONSERVATIVE 입출금(마감 전 신용카드 사용 추정)을 더하고, 추정 하루 생활비를 내일부터 그날까지 날마다 뺀다
 * (오늘 쓴 돈은 현재 잔액에 이미 반영됨). 생활비 추정이 없는 계좌는 보수적 선이 null이라 CONSERVATIVE 입출금도 보이지 않는다.
 * 통합 선은 같은 날짜의 계좌별 잔액 합이라, 상세의 계좌별 수치를 더하면 항상 통합 수치와 같다(FR-039).
 */
final class ForecastCalculator {

    private ForecastCalculator() {
    }

    /**
     * from~to(포함) 기간의 통합·계좌별 날짜 선을 돌려준다. 각 점은 그날 입출금을 모두 반영한 일말 잔액이다.
     * 선에는 기간의 모든 날짜에 점을 둔다(입출금이 없는 날은 직전 잔액). 그래서 같은 날짜의 계좌별 값을 더하면
     * 통합 값과 같고, 날마다 줄어드는 선(생활비)의 최저점도 놓치지 않는다. 기간 밖 입출금은 무시한다.
     *
     * @param startBalances   선택한 계좌별 현재 잔액(원). 순서대로 결과에 담는다
     * @param events          미래 입출금. 계좌는 startBalances에 있어야 한다
     * @param dailyLivingCost 계좌별 추정 하루 생활비(원). 없는 계좌는 보수적 선을 null로 두고, 그러면 통합 보수적 선도 null이다
     * @return 통합 선과 계좌별 선
     */
    static Projection project(Map<Long, Long> startBalances, List<FlowEvent> events, LocalDate from, LocalDate to,
            Map<Long, Long> dailyLivingCost) {
        if (startBalances.isEmpty()) {
            throw new IllegalArgumentException("예보할 계좌가 없다.");
        }
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("예보 기간이 거꾸로다: " + from + " ~ " + to);
        }
        Map<Long, TreeMap<LocalDate, List<FlowEvent>>> eventsByAccount = new LinkedHashMap<>();
        startBalances.keySet().forEach(accountId -> eventsByAccount.put(accountId, new TreeMap<>()));
        List<LocalDate> dates = from.datesUntil(to.plusDays(1)).toList();
        for (FlowEvent event : events) {
            if (event.date().isBefore(from) || event.date().isAfter(to)) {
                continue;
            }
            TreeMap<LocalDate, List<FlowEvent>> byDate = eventsByAccount.get(event.accountId());
            // 선택하지 않은 계좌의 입출금을 다른 계좌에 옮겨 담지 않는다. 걸러 내는 것은 호출하는 쪽 책임이다.
            if (byDate == null) {
                throw new IllegalArgumentException("선택하지 않은 계좌의 입출금이다: " + event.accountId());
            }
            byDate.computeIfAbsent(event.date(), date -> new ArrayList<>()).add(event);
        }

        Map<LocalDate, Totals> totals = new TreeMap<>();
        dates.forEach(date -> totals.put(date, new Totals()));
        Map<Long, List<BalancePoint>> byAccount = new LinkedHashMap<>();
        for (Map.Entry<Long, TreeMap<LocalDate, List<FlowEvent>>> account : eventsByAccount.entrySet()) {
            long confirmed = startBalances.get(account.getKey());
            long expected = confirmed;
            long conservativeOnly = 0;
            Long living = dailyLivingCost.get(account.getKey());
            List<BalancePoint> points = new ArrayList<>();
            for (int day = 0; day < dates.size(); day++) {
                LocalDate date = dates.get(day);
                List<FlowEvent> dayEvents = account.getValue().getOrDefault(date, List.of());
                for (FlowEvent event : dayEvents) {
                    switch (event.confidence()) {
                        case CONFIRMED -> {
                            confirmed = Math.addExact(confirmed, event.amount());
                            expected = Math.addExact(expected, event.amount());
                        }
                        case EXPECTED -> expected = Math.addExact(expected, event.amount());
                        case CONSERVATIVE -> conservativeOnly = Math.addExact(conservativeOnly, event.amount());
                    }
                }
                Long livingToDate = living == null ? null : Math.multiplyExact(living.longValue(), day);
                Long conservative = living == null ? null
                        : Math.subtractExact(Math.addExact(expected, conservativeOnly), livingToDate);
                totals.get(date).add(confirmed, expected, conservative, livingToDate, dayEvents);
                points.add(new BalancePoint(date, confirmed, expected, conservative, livingToDate,
                        List.copyOf(dayEvents)));
            }
            byAccount.put(account.getKey(), List.copyOf(points));
        }
        List<BalancePoint> total = totals.entrySet().stream()
                .map(entry -> new BalancePoint(entry.getKey(), entry.getValue().confirmed, entry.getValue().expected,
                        entry.getValue().conservative, entry.getValue().livingCost,
                        List.copyOf(entry.getValue().events)))
                .toList();
        return new Projection(total, byAccount);
    }

    /**
     * 그날 입출금을 모두 반영한 일말 잔액.
     *
     * @param confirmed    확정 선 잔액(원)
     * @param expected     예상 선 잔액(원)
     * @param conservative 보수적 선 잔액(원). 생활비 추정이 없으면 null
     * @param livingCost   보수적 선에서 그날까지 뺀 추정 생활비 합계(원, 0 이상). 보수적 선이 null이면 null
     * @param events       그날 반영한 입출금
     */
    record BalancePoint(LocalDate date, long confirmed, long expected, Long conservative, Long livingCost,
            List<FlowEvent> events) {}

    /**
     * 통합 선과 계좌별 선.
     *
     * @param total     같은 날짜 계좌별 잔액의 합
     * @param byAccount 계좌 번호별 선(입력 계좌 순서)
     */
    record Projection(List<BalancePoint> total, Map<Long, List<BalancePoint>> byAccount) {}

    /** 날짜 하나의 통합 합계를 모은다. */
    private static final class Totals {
        private long confirmed;
        private long expected;
        /** 한 계좌라도 보수적 선이 없으면 null로 남겨 일부만 더한 값을 내지 않는다. */
        private Long conservative = 0L;
        private Long livingCost = 0L;
        private final List<FlowEvent> events = new ArrayList<>();

        void add(long accountConfirmed, long accountExpected, Long accountConservative, Long accountLivingCost,
                List<FlowEvent> dayEvents) {
            confirmed = Math.addExact(confirmed, accountConfirmed);
            expected = Math.addExact(expected, accountExpected);
            conservative = conservative == null || accountConservative == null
                    ? null : Math.addExact(conservative, accountConservative);
            livingCost = livingCost == null || accountLivingCost == null
                    ? null : Math.addExact(livingCost, accountLivingCost);
            events.addAll(dayEvents);
        }
    }
}
