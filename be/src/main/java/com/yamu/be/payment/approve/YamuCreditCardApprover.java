package com.yamu.be.payment.approve;

import com.yamu.be.card.CardQueryService;
import com.yamu.be.card.UserCardRef;
import com.yamu.be.card.domain.CardType;
import com.yamu.be.global.calendar.BusinessDayCalendar;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.payment.PaymentTerms;
import com.yamu.be.payment.approve.CreditLimitSource.LimitCheck;
import com.yamu.be.payment.billing.BillStatus;
import com.yamu.be.payment.billing.BillingCycle;
import com.yamu.be.payment.billing.CardBillRepository;
import com.yamu.be.payment.billing.InstallmentPlan;
import com.yamu.be.payment.recommend.RecommendationResult.BenefitLine;
import com.yamu.be.payment.recommend.RecommendationResult.Candidate;
import com.yamu.be.payment.recommend.RecommendationResult.CardSummary;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 신용카드 모의 승인(Task 5.2.4, 명세 「결제 승인 요청」, POL-013). 계좌는 건드리지 않는다.
 *
 * <pre>
 * 사전 검사(승인 전): 결제일 없음·청구 주기를 만들 수 없는 결제일(1~27 밖)·마감 일수(1~31 밖)·한도 자료 없음
 *                   → 422 DATA_INSUFFICIENT (PROCESSING·시도 횟수를 쓰지 않음)
 * 승인 실행: 미납 청구서가 있는 카드면 DECLINED UNPAID_BILL, 즉시할인 뒤 원금이 0원이면 회차 없이 승인,
 *           즉시할인 뒤 전체 원금이 가용 한도를 넘으면 DECLINED CREDIT_LIMIT_EXCEEDED,
 *           아니면 승인하고 청구 계획(회차별 원금·청구 월·출금 예정일)을 만든다
 * </pre>
 *
 * <p>원금은 결제 금액 − 즉시할인이다(S15P31E103-292). 할인 뒤 금액이 할부 최소 금액(5만 원)보다 작아져도 POS가 정한 할부 조건은
 * 그대로 둔다. 청구할인·캐시백·적립은 원금에서 빼지 않는다.
 *
 * <p>청구 회차는 결과 저장이 {@code CreditBillingWriter}로 적는다. 실적 인정액은 BE3가 계산한 값이다(Task 5.2.11, 미확인은 null).
 * 미납 해소 방법이 정해지지 않아(FR-069) 한 번 미납된 카드는 계속 거절된다(임시).
 */
@Slf4j
@Component
public class YamuCreditCardApprover implements CreditCardApprover {

    private final CardQueryService cardQuery;
    private final CreditLimitSource limits;
    private final CardBillRepository bills;
    private final BusinessDayCalendar calendar;

    public YamuCreditCardApprover(CardQueryService cardQuery, CreditLimitSource limits, CardBillRepository bills,
            BusinessDayCalendar calendar) {
        this.cardQuery = cardQuery;
        this.limits = limits;
        this.bills = bills;
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
        if (!BillingCycle.supports(ref.paymentDay(), ref.billingCutoffDays())) {
            // DB는 결제일 1~31·마감 일수 1 이상을 허용한다. 여기서 거르지 않으면 승인 실행 중 예외로 PROCESSING에 남는다
            throw new BusinessException(ErrorCode.DATA_INSUFFICIENT, "이 카드의 결제일·이용기간 정보로는 청구 일정을 만들 수 없습니다.",
                    Map.of("reason", "BILLING_CYCLE_UNSUPPORTED"));
        }
        if (limits.check(userId, card.userCardId(), terms.amount()).result() == CreditLimitSource.Result.UNKNOWN) {
            throw new BusinessException(ErrorCode.DATA_INSUFFICIENT, "이 카드의 가용 한도를 확인할 수 없습니다.",
                    Map.of("reason", "CREDIT_LIMIT_UNKNOWN"));
        }
    }

    @Override
    public ApprovalOutcome approve(ApprovalCommand command) {
        // 청구 원금 = 결제 금액 − 즉시할인(결제 화면이 보여 준 금액). 청구할인·캐시백·적립은 여기서 빼지 않는다
        long amount = command.chargedAmount();
        UserCardRef ref = registered(command.userId(), command.card().userCardId());
        if (ref.paymentDay() == null) {
            // 사전 검사에서 걸렀어야 한다. 결제일 없이는 청구 월을 정할 수 없으므로 승인하지 않는다
            throw new BusinessException(ErrorCode.DATA_INSUFFICIENT, "이 카드의 결제일 정보가 없어 청구 일정을 만들 수 없습니다.");
        }
        if (bills.existsByUserCardIdAndStatus(command.card().userCardId(), BillStatus.UNPAID)) {
            log.info("신용 승인 거절: 미납 청구서가 있는 카드 paymentId={} card=****{}", command.paymentId(),
                    command.card().last4());
            return ApprovalOutcome.Declined.of(FailureCode.UNPAID_BILL);
        }
        LocalDate performanceMonth = command.performanceMonth();
        Long recognized = command.recognizedPerformanceAmount();
        Candidate basis = command.candidate();
        List<BenefitLine> applied = ApprovalOutcome.Approved.applied(basis);
        if (amount == 0) {
            // 전액 즉시할인: 청구할 원금이 없으므로 한도를 보지 않고 회차 없이 승인한다(체크카드 0원 승인과 같음)
            log.info("신용 0원 승인, 청구 회차 없음 paymentId={}", command.paymentId());
            return new ApprovalOutcome.Approved(0, 0, recognized, performanceMonth, applied, null, null, null, basis);
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
        // 승인액 = 청구 원금(즉시할인 뒤). 혜택·한도 사용은 승인 때 다시 계산한 후보 그대로 원장에 적는다(Task 5.2.5).
        // 실적 인정액은 BE3가 계산한 값 그대로다(Task 5.2.11, 미확인은 null)
        return new ApprovalOutcome.Approved(amount, amount, recognized, performanceMonth, applied, null, null, plan, basis);
    }

    private UserCardRef registered(Long userId, Long userCardId) {
        return cardQuery.findRegisteredCard(userId, userCardId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }
}
