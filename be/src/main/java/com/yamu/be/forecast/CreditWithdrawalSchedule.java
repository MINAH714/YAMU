package com.yamu.be.forecast;

import java.time.LocalDate;

/**
 * 신용카드 사용일의 청구 출금일을 정한다(FR-036: 이용기간 마감·결제일·비영업일이면 다음 영업일).
 *
 * <p>계산은 Task 7.2.1(가상 구매 청구 일정)에서 만든다. BE 2 청구(card_bill)와 같은 규칙이어야 해서
 * 계산 함수를 BE 2와 공유할지는 7.2.1에서 BE 2와 정한다. 그전까지 잔고예보는 이 계약만 쓴다.
 */
interface CreditWithdrawalSchedule {

    /**
     * 신용카드로 spendDate에 쓴 금액이 계좌에서 빠지는 날을 돌려준다.
     *
     * @return 한국 날짜 기준 출금 예정일
     */
    LocalDate withdrawalDate(CardReader.CardInfo card, LocalDate spendDate);
}
