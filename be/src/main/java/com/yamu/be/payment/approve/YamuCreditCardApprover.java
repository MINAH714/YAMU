package com.yamu.be.payment.approve;

import com.yamu.be.card.CardQueryService;
import com.yamu.be.card.UserCardRef;
import com.yamu.be.card.domain.CardType;
import com.yamu.be.global.calendar.BusinessDayCalendar;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.payment.PaymentTerms;
import com.yamu.be.payment.approve.CreditLimitSource.LimitCheck;
import com.yamu.be.payment.billing.BillingCycle;
import com.yamu.be.payment.billing.InstallmentPlan;
import com.yamu.be.payment.recommend.RecommendationResult.CardSummary;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 신용카드 모의 승인(Task 5.2.4, 명세 「결제 승인 요청」, POL-013). 계좌는 건드리지 않는다.
 *
 * <pre>
 * 사전 검사(승인 전): 결제일 없음·한도 자료 없음 → 422 DATA_INSUFFICIENT (PROCESSING·시도 횟수를 쓰지 않음)
 * 승인 실행: 전체 원금이 가용 한도를 넘으면 DECLINED CREDIT_LIMIT_EXCEEDED, 아니면 승인하고 청구 계획(회차별 원금·청구 월·출금 예정일)을 만든다
 * </pre>
 *
 * 청구 회차는 결과 저장이 {@code CreditBillingWriter}로 적는다. 혜택 재계산·실적 인정액은 BE3·Task 5.2.5 전이라 0이다(임시).
 */
@Slf4j
@Component
public class YamuCreditCardApprover implements CreditCardApprover {

    private final CardQueryService cardQuery;
    private final CreditLimitSource limits;
    private final BusinessDayCalendar calendar;

    public YamuCreditCardApprover(CardQueryService cardQuery, CreditLimitSource limits, BusinessDayCalendar calendar) {
        this.cardQuery = cardQuery;
        this.limits = limits;
        this.calendar = calendar;
    }

    @Override
    public void precheck(Long userId, CardSummary card, PaymentTerms terms) {
        if (card.cardType() != CardType.CREDIT) {
            return;
        }
        UserCardRef ref = registered(userId, card.userCardId());
        if (ref.paymentDay() == null) {
            throw new BusinessException(ErrorCode.DATA_INSUFFICIENT, "이 카드의 결제일 정보가 없어 청구 일정을 만들 수 없습니다.",
                    Map.of("reason", "PAYMENT_DAY_MISSING"));
        }
        if (limits.check(userId, card.userCardId(), terms.amount()).result() == CreditLimitSource.Result.UNKNOWN) {
            throw new BusinessException(ErrorCode.DATA_INSUFFICIENT, "이 카드의 가용 한도를 확인할 수 없습니다.",
                    Map.of("reason", "CREDIT_LIMIT_UNKNOWN"));
        }
    }

    @Override
    public ApprovalOutcome approve(ApprovalCommand command) {
        long amount = command.terms().amount();
        UserCardRef ref = registered(command.userId(), command.card().userCardId());
        if (ref.paymentDay() == null) {
            // 사전 검사에서 걸렀어야 한다. 결제일 없이는 청구 월을 정할 수 없으므로 승인하지 않는다
            throw new BusinessException(ErrorCode.DATA_INSUFFICIENT, "이 카드의 결제일 정보가 없어 청구 일정을 만들 수 없습니다.");
        }
        LimitCheck limit = limits.check(command.userId(), command.card().userCardId(), amount);
        if (limit.result() == CreditLimitSource.Result.EXCEEDED) {
            log.info("신용 승인 거절: 전체 원금이 가용 한도 초과 paymentId={} principal={} available={}", command.paymentId(),
                    amount, limit.availableLimit());
            return new ApprovalOutcome.Declined(FailureCode.CREDIT_LIMIT_EXCEEDED, null,
                    limit.availableLimit() == null ? null : Map.<String, Object>of("availableLimit", limit.availableLimit()));
        }
        if (limit.result() == CreditLimitSource.Result.UNKNOWN) {
            // 사전 검사에서 걸렀어야 한다. 여기까지 왔으면 승인하지 않고 운영 확인
            throw new BusinessException(ErrorCode.DATA_INSUFFICIENT, "이 카드의 가용 한도를 확인할 수 없습니다.");
        }
        BillingCycle cycle = BillingCycle.of(ref.paymentDay(), ref.billingCutoffDays());
        InstallmentPlan plan = InstallmentPlan.of(amount, command.terms().installmentMonthsOrOne(), cycle,
                ApprovalDates.koreanDate(command.now()), calendar);
        log.info("신용 승인 paymentId={} amount={} months={} firstBillingMonth={}", command.paymentId(), amount,
                plan.months(), plan.installments().get(0).billingMonth());
        // 임시: 혜택·실적 계산 전이라 승인액 = 원금, 실적 인정액 0 (BE3·Task 5.2.5)
        return new ApprovalOutcome.Approved(amount, amount, 0, ApprovalDates.performanceMonth(command.now()),
                List.of(), null, null, plan);
    }

    private UserCardRef registered(Long userId, Long userCardId) {
        return cardQuery.findRegisteredCard(userId, userCardId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }
}
