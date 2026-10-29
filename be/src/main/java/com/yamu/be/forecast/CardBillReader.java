package com.yamu.be.forecast;

import java.time.LocalDate;
import java.util.List;

/**
 * 선택 계좌에서 나갈 신용카드 청구 출금 예정을 읽는다.
 *
 * <p>임시 계약(BE 2 확인 필요): BE 2 담당 부재로 잔고예보가 필요한 모양을 먼저 정했다. MR 리뷰에서 BE 2 확인을 받는다.
 * 기대하는 동작:
 * <ul>
 *   <li>출금이 끝난 청구는 넣지 않는다(이미 현재 잔액에 반영됨).</li>
 *   <li>accountIds는 청구 확정 당시 출금 계좌 기준이며, 다른 계좌의 청구는 넣지 않는다.</li>
 *   <li>confirmed는 청구액이 확정됐는지(명세의 CONFIRMED). 이용기간이 진행 중이면 false(ACCUMULATING).</li>
 * </ul>
 */
interface CardBillReader {

    /**
     * from~to(포함)에 출금 예정인 청구를 돌려준다.
     *
     * @return 출금 예정 청구
     */
    List<BillWithdrawal> upcoming(Long userId, List<Long> accountIds, LocalDate from, LocalDate to);

    /**
     * @param accountId      출금 계좌
     * @param withdrawalDate 한국 날짜 기준 출금 예정일
     * @param amount         출금할 금액(원, 0 이상)
     * @param confirmed      청구액 확정 여부
     */
    record BillWithdrawal(Long cardBillId, Long accountId, LocalDate withdrawalDate, long amount,
            boolean confirmed) {}
}
