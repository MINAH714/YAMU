package com.yamu.be.assetflow;

import com.yamu.be.payment.bank.BankDirection;
import com.yamu.be.payment.bank.BankHistory;
import com.yamu.be.payment.bank.BankTransaction;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 완전한 과거 거래만으로 한국 날짜 일말 잔고를 구성하는 순수 계산기.
 * 금융망·DB 조회/변경은 하지 않는다. 호출 측이 계좌 소유권과 조회 시각 정합성을 검증한다.
 * 동일 시각 거래는 번호 순서로 추정하지 않고 미확인으로 둔다.
 */
public final class DailyBalanceReconstructor {
    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
    public enum Reason { OK, HISTORY_INCOMPLETE, FILTERED_HISTORY, NO_ANCHOR, AMBIGUOUS_ORDER,
        BALANCE_MISMATCH, ARITHMETIC_OVERFLOW }

    public record Point(LocalDate date, Long balance) {
        @Override public String toString() { return "Point[date=" + date + ", available=" + (balance != null) + "]"; }
    }

    public record Result(LocalDate from, LocalDate to, Reason reason, List<Point> points) {
        public Result { points = List.copyOf(points); }
        @Override public String toString() {
            return "DailyBalances[from=" + from + ", to=" + to + ", reason=" + reason + ", days=" + points.size() + "]";
        }
    }

    /**
     * @param verifiedOpeningBalance 조회 시작일 직전 일말의 확인된 잔고. 없으면 null.
     *      현재 잔고 캐시를 이 값에 넣지 않는다. 첫 거래가 있으면 거래 전 잔고를 역산할 수 있다.
     */
    public Result reconstruct(BankHistory history, Long verifiedOpeningBalance) {
        Objects.requireNonNull(history);
        if (!history.to().isBefore(history.queriedAt().atZone(KOREA).toLocalDate())) {
            throw new IllegalArgumentException("일말 실제 잔고는 조회 시점 이전 날짜만 구성합니다.");
        }
        if (!history.complete()) return unavailable(history, Reason.HISTORY_INCOMPLETE);
        if (history.direction() != null) return unavailable(history, Reason.FILTERED_HISTORY);
        var rows = history.transactions().stream()
                .sorted(Comparator.comparing(BankTransaction::date).thenComparing(BankTransaction::time)).toList();
        for (int i = 1; i < rows.size(); i++) {
            var before = rows.get(i - 1);
            var after = rows.get(i);
            if (before.date().equals(after.date()) && before.time().equals(after.time())) {
                return unavailable(history, Reason.AMBIGUOUS_ORDER);
            }
        }
        if (verifiedOpeningBalance == null && rows.isEmpty()) return unavailable(history, Reason.NO_ANCHOR);
        try {
            long running = verifiedOpeningBalance != null ? verifiedOpeningBalance : before(rows.get(0));
            int index = 0;
            var points = new ArrayList<Point>();
            for (LocalDate date = history.from(); !date.isAfter(history.to()); date = date.plusDays(1)) {
                while (index < rows.size() && rows.get(index).date().equals(date)) {
                    var row = rows.get(index++);
                    running = row.direction() == BankDirection.DEPOSIT
                            ? Math.addExact(running, row.amount()) : Math.subtractExact(running, row.amount());
                    if (running != row.balanceAfter()) return unavailable(history, Reason.BALANCE_MISMATCH);
                }
                points.add(new Point(date, running));
            }
            return new Result(history.from(), history.to(), Reason.OK, points);
        } catch (ArithmeticException overflow) {
            return unavailable(history, Reason.ARITHMETIC_OVERFLOW);
        }
    }

    private long before(BankTransaction row) {
        return row.direction() == BankDirection.DEPOSIT
                ? Math.subtractExact(row.balanceAfter(), row.amount()) : Math.addExact(row.balanceAfter(), row.amount());
    }

    private Result unavailable(BankHistory history, Reason reason) {
        var points = new ArrayList<Point>();
        for (LocalDate date = history.from(); !date.isAfter(history.to()); date = date.plusDays(1)) {
            points.add(new Point(date, null));
        }
        return new Result(history.from(), history.to(), reason, points);
    }
}
