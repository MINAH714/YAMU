package com.yamu.be.assetflow;

import com.yamu.be.account.domain.LinkedAccount;
import com.yamu.be.global.crypto.CipherField;
import com.yamu.be.global.crypto.PersonalDataCipher;
import com.yamu.be.global.finapi.FinApiException;
import com.yamu.be.payment.bank.BankHistory;
import com.yamu.be.payment.bank.SsafyTransferApi;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/** 호출 측에서 모든 계좌의 소유권을 먼저 확인한다. 금융망 호출 중 DB 트랜잭션을 유지하지 않는다. */
@Service
class AssetFlowHistoryReader {
    private final SsafyTransferApi bank;
    private final PersonalDataCipher cipher;
    AssetFlowHistoryReader(SsafyTransferApi bank, PersonalDataCipher cipher) {
        this.bank = bank;
        this.cipher = cipher;
    }
    BankHistory read(String userKey, LinkedAccount account, LocalDate from, LocalDate to, long deadline) {
        String accountNo = cipher.decrypt(account.getAccountNoEnc(), CipherField.LINKED_ACCOUNT_NO);
        return readRange(userKey, accountNo, from, to, deadline);
    }
    private BankHistory readRange(String userKey, String accountNo, LocalDate from, LocalDate to, long deadline) {
        if (System.nanoTime() >= deadline) throw FinApiException.timedOut("assetFlowHistory");
        BankHistory result = bank.historySnapshot(userKey, accountNo, from, to, null);
        if (result.integrity() != BankHistory.Integrity.COUNT_MISMATCH || from.equals(to)) return result;
        LocalDate mid = from.plusDays(ChronoUnit.DAYS.between(from, to) / 2);
        BankHistory left = readRange(userKey, accountNo, from, mid, deadline);
        BankHistory right = readRange(userKey, accountNo, mid.plusDays(1), to, deadline);
        var rows = new ArrayList<>(left.transactions());
        rows.addAll(right.transactions());
        // 어느 조각이든 불완전하면 합친 목록의 크기를 완전성 근거로 사용하지 않는다.
        Long count = left.complete() && right.complete() ? (long) rows.size() : null;
        return new BankHistory(from, to, null, count, List.copyOf(rows), right.queriedAt());
    }
}
