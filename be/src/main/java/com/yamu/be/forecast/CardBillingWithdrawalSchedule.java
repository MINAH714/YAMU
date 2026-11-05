package com.yamu.be.forecast;

import com.yamu.be.card.UserCardRef;
import com.yamu.be.global.calendar.BusinessDayCalendar;
import com.yamu.be.payment.billing.BillingCycle;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 신용카드 청구 출금일을 BE 2 청구 주기({@link BillingCycle})와 영업일 계산기로 정한다.
 * 신용 승인이 card_bill의 출금 예정일을 만드는 계산과 같아서, 예정 지출과 실제 청구의 출금일이 어긋나지 않는다.
 *
 * <p>결제일이 없거나 DB가 허용하지만 청구 주기를 만들 수 없는 값(결제일 28~31 등)이면 empty다.
 * 신용 승인도 이런 카드는 거절한다({@code PAYMENT_DAY_MISSING}·{@code BILLING_CYCLE_UNSUPPORTED}).
 */
@Component
class CardBillingWithdrawalSchedule implements CreditWithdrawalSchedule {

    private final BusinessDayCalendar calendar;

    CardBillingWithdrawalSchedule(BusinessDayCalendar calendar) {
        this.calendar = calendar;
    }

    @Override
    public Optional<LocalDate> withdrawalDate(UserCardRef card, LocalDate spendDate) {
        Integer paymentDay = card.paymentDay();
        if (paymentDay == null || !BillingCycle.supports(paymentDay, card.billingCutoffDays())) {
            return Optional.empty();
        }
        // TODO(S15P31E103-205): 결제일을 바꾸면 다음 청구서부터 적용된다(명세 「청구서 목록」). 지금은 현재 결제일로만 센다.
        LocalDate paymentDate = BillingCycle.of(paymentDay, card.billingCutoffDays()).cycleFor(spendDate).paymentDate();
        return Optional.of(calendar.businessDayOnOrAfter(paymentDate));
    }
}
