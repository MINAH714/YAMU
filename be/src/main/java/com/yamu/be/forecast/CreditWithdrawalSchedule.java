package com.yamu.be.forecast;

import com.yamu.be.card.UserCardRef;
import java.time.LocalDate;
import java.util.Optional;

/**
 * 신용카드 사용일의 청구 출금일을 정한다(FR-036: 이용기간 마감·결제일·비영업일이면 다음 영업일).
 * 구현은 {@link CardBillingWithdrawalSchedule}이다. 실제 청구서(BE 2 card_bill)와 같은 계산을 쓴다.
 */
interface CreditWithdrawalSchedule {

    /**
     * 신용카드로 spendDate에 쓴 금액이 계좌에서 빠지는 날을 돌려준다.
     *
     * @param card BE 1 카드 정보. 결제일·이용기간 마감 일수(paymentDay·billingCutoffDays)를 쓴다
     * @return 한국 날짜 기준 출금 예정일. 결제일이 없거나 청구 주기를 만들 수 없는 값이면 empty
     */
    Optional<LocalDate> withdrawalDate(UserCardRef card, LocalDate spendDate);
}
