package com.yamu.be.payment.bank;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;

/**
 * SSAFY 계좌 거래내역 한 줄.
 *
 * @param bankTransactionNo SSAFY 거래 번호(transactionUniqueNo)
 * @param date              SSAFY 서버가 찍은 거래일(한국 날짜). 요청할 때 날짜를 정할 수 없다
 * @param time              SSAFY 서버가 찍은 거래 시각(한국 시각)
 * @param amount            거래 금액(원). 잔액이 아니다
 * @param balanceAfter      거래 후 잔액(원)
 * @param summary           요약. YAMU가 보낸 거래면 태그({@link TransferTag})가 들어 있다. 없으면 빈 글자
 */
public record BankTransaction(String bankTransactionNo, LocalDate date, LocalTime time, BankDirection direction,
        long amount, long balanceAfter, String summary) {

    /** 요약이 YAMU 태그면 그 태그. "초기 입금"처럼 YAMU가 보내지 않은 거래는 비어 있다. */
    public Optional<TransferTag> tag() {
        return TransferTag.parse(summary);
    }
}
