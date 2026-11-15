package com.yamu.be.payment.bank;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** 금융망 조회 결과와 커버리지. 계좌번호·userKey는 담지 않는다. direction=null이면 입출금 전체다. */
public record BankHistory(LocalDate from, LocalDate to, BankDirection direction, Long reportedCount,
        List<BankTransaction> transactions, Instant queriedAt) {

    public enum Integrity {
        COMPLETE, UNKNOWN_COUNT, COUNT_MISMATCH, DUPLICATE_TRANSACTION, OUT_OF_RANGE, INVALID_TRANSACTION,
        WRONG_DIRECTION
    }

    public BankHistory {
        Objects.requireNonNull(from);
        Objects.requireNonNull(to);
        Objects.requireNonNull(queriedAt);
        if (from.isAfter(to)) throw new IllegalArgumentException("조회 시작일은 종료일 이후일 수 없습니다.");
        transactions = List.copyOf(transactions);
    }

    public Integrity integrity() {
        if (reportedCount == null || reportedCount < 0) return Integrity.UNKNOWN_COUNT;
        if (reportedCount != transactions.size()) return Integrity.COUNT_MISMATCH;
        var ids = new HashSet<String>();
        for (var row : transactions) {
            if (row.bankTransactionNo() == null || row.bankTransactionNo().isBlank()
                    || row.date() == null || row.time() == null || row.direction() == null || row.amount() < 0) {
                return Integrity.INVALID_TRANSACTION;
            }
            if (!ids.add(row.bankTransactionNo())) return Integrity.DUPLICATE_TRANSACTION;
            if (row.date().isBefore(from) || row.date().isAfter(to)) return Integrity.OUT_OF_RANGE;
            if (direction != null && direction != row.direction()) return Integrity.WRONG_DIRECTION;
        }
        return Integrity.COMPLETE;
    }

    public boolean complete() { return integrity() == Integrity.COMPLETE; }

    @Override public String toString() {
        return "BankHistory[from=" + from + ", to=" + to + ", count=" + transactions.size()
                + ", integrity=" + integrity() + ", queriedAt=" + queriedAt + "]";
    }
}
