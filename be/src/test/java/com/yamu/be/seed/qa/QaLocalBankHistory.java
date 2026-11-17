package com.yamu.be.seed.qa;

import com.yamu.be.payment.bank.*;
import java.time.*;
import java.util.*;

/** Local preview only: replay the funding evidence installed with the historical ledger. */
final class QaLocalBankHistory {
    private final Map<String, BankTransaction> funding;
    private final Clock clock;

    QaLocalBankHistory(List<QaSeedSpec.Account> accounts, Clock clock) {
        this.clock = clock;
        var rows = new HashMap<String, BankTransaction>();
        for (int i = 0; i < accounts.size(); i++) {
            long amount = QaLifeScenario.startingBalance(i);
            var row = new BankTransaction("LOCAL-FIXTURE-FUNDING-" + i, QaLifeScenario.BASE,
                    LocalTime.NOON, BankDirection.DEPOSIT, amount, amount, "LOCAL TEST FIXTURE");
            if (rows.put(accounts.get(i).accountNo(), row) != null)
                throw new IllegalArgumentException("Duplicate local fixture account");
        }
        funding = Map.copyOf(rows);
    }

    BankHistory snapshot(String account, LocalDate from, LocalDate to, BankDirection direction) {
        var row = funding.get(account);
        if (row == null) throw new IllegalArgumentException("Unknown local fixture account");
        var rows = !row.date().isBefore(from) && !row.date().isAfter(to)
                && (direction == null || row.direction() == direction) ? List.of(row) : List.<BankTransaction>of();
        return new BankHistory(from, to, direction, (long) rows.size(), rows, clock.instant());
    }
}
