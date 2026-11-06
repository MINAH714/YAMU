package com.yamu.be.forecast;

import com.yamu.be.card.UserCardRef;
import com.yamu.be.card.domain.CardIssuerStatus;
import com.yamu.be.card.domain.CardType;
import com.yamu.be.payment.billing.BillingCycle;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 내일부터 이번 이용기간 마감일까지 쓸 신용카드 금액을 카드마다 추정해, 그 청구서의 출금일에 보수적 선에서만 빼는 출금으로 만든다
 * (S15P31E103-286, 2026-11-06 설계 결정). DB·외부 호출 없이 입력만으로 계산한다.
 *
 * <p>진행 중 청구서에는 오늘까지 쓴 금액만 있고 생활비는 체크카드만 보므로, 앞으로 쓸 신용카드 금액은 예보 어디에도 없었다.
 *
 * <ul>
 *   <li>대상: 출금 계좌가 선택 계좌이고 카드사 상태가 정상(NORMAL)인 등록 신용카드. 추천에서만 뺀 카드(INACTIVE)도 결제는 되므로 넣는다.
 *       정지·만료 카드와 결제일·청구 주기를 만들 수 없는 카드는 승인이 거절돼 앞으로 쓸 금액이 없으므로 조용히 뺀다.</li>
 *   <li>일평균: 그 카드의 신용 LIVING 결제를 지난 3개월 실적 요약과 같은 규칙({@link LivingCostEstimator}, 완료한 달만, REAL 우선)으로
 *       계산한다. 카드 번호를 묶음 번호로 넣어 그대로 쓴다.</li>
 *   <li>금액: 일평균 × (이번 이용기간 마감일 − 오늘)일. 오늘 쓴 금액은 진행 중 청구서에 이미 들어 있어 내일부터 센다.
 *       마감일이 오늘이면 0일이라 넣지 않는다. 0원도 넣지 않는다.</li>
 *   <li>날짜: 이번 이용기간 청구서의 출금일({@link CreditWithdrawalSchedule}, 지금 결제일 기준). 예보 기간 뒤면 넣지 않는다.</li>
 *   <li>완료한 달이 없어 일평균을 모르는 카드는 출금일이 예보 기간 안일 때만 {@code CREDIT_SPENDING_UNKNOWN}으로 알린다.</li>
 * </ul>
 */
final class CreditSpendingEstimator {

    private CreditSpendingEstimator() {
    }

    /**
     * @param cards        BE 1로 확인한 등록 카드(ACTIVE·INACTIVE). 신용카드가 아니거나 선택 계좌가 아닌 카드는 무시한다
     * @param names        카드 번호별 화면 이름(별칭, 없으면 상품명)
     * @param days         카드별 일별 신용 LIVING 결제 합계({@link CreditSpendingReader})
     * @param selected     선택 계좌
     * @param today        한국 날짜 오늘(예보 시작일)
     * @param to           예보 마지막 날(포함)
     * @param schedule     신용카드 사용일 → 청구 출금일
     * @return 보수적 선 전용 출금과 알릴 항목
     */
    static Result estimate(List<UserCardRef> cards, Map<Long, String> names,
            List<CreditSpendingReader.CardDailySpending> days, Set<Long> selected, LocalDate today, LocalDate to,
            CreditWithdrawalSchedule schedule) {
        List<Long> cardIds = cards.stream().map(UserCardRef::userCardId).toList();
        List<LivingCostReader.DailySpending> rows = days.stream()
                .map(day -> new LivingCostReader.DailySpending(day.userCardId(), day.date(), day.amount(), day.amount(),
                        day.origin(), true))
                .toList();
        Map<Long, LivingCostEstimator.AccountEstimate> averages =
                LivingCostEstimator.estimate(cardIds, rows, today).byAccount();

        List<FlowEvent> events = new ArrayList<>();
        List<ExcludedItem> excluded = new ArrayList<>();
        for (UserCardRef card : cards) {
            if (card.cardType() != CardType.CREDIT || card.issuerStatus() != CardIssuerStatus.NORMAL
                    || card.linkedAccountId() == null || !selected.contains(card.linkedAccountId())
                    || card.paymentDay() == null || !BillingCycle.supports(card.paymentDay(), card.billingCutoffDays())) {
                continue;
            }
            Optional<LocalDate> withdrawal = schedule.withdrawalDate(card, today);
            if (withdrawal.isEmpty() || withdrawal.get().isAfter(to)) {
                continue;
            }
            LocalDate periodEnd = BillingCycle.of(card.paymentDay(), card.billingCutoffDays()).cycleFor(today).periodEnd();
            long remainingDays = ChronoUnit.DAYS.between(today, periodEnd);
            if (remainingDays <= 0) {
                continue;
            }
            String name = names.getOrDefault(card.userCardId(), "신용카드");
            Long dailyAverage = averages.get(card.userCardId()).dailyAverage();
            if (dailyAverage == null) {
                excluded.add(new ExcludedItem(null, null, null, withdrawal.get(), name, null,
                        ExcludedItem.Reason.CREDIT_SPENDING_UNKNOWN));
                continue;
            }
            long amount = Math.multiplyExact(dailyAverage.longValue(), remainingDays);
            if (amount == 0) {
                continue;
            }
            events.add(new FlowEvent(card.linkedAccountId(), withdrawal.get(), -amount,
                    FlowEvent.Type.CARD_SPENDING_ESTIMATE, FlowEvent.Confidence.CONSERVATIVE,
                    title(name, today.plusDays(1), periodEnd, remainingDays, dailyAverage), card.userCardId()));
        }
        return new Result(List.copyOf(events), List.copyOf(excluded));
    }

    /** 예: "생활카드 · 11/7~11/11 5일 × 일평균 12,000원". 응답에서 추정 근거로 보인다. */
    static String title(String name, LocalDate from, LocalDate to, long days, long dailyAverage) {
        return String.format(Locale.KOREA, "%s · %d/%d~%d/%d %d일 × 일평균 %,d원", name, from.getMonthValue(),
                from.getDayOfMonth(), to.getMonthValue(), to.getDayOfMonth(), days, dailyAverage);
    }

    /**
     * @param events   카드 출금 계좌·출금일의 보수적 선 전용 출금(CARD_SPENDING_ESTIMATE, CONSERVATIVE). refId는 카드 번호
     * @param excluded 일평균을 몰라 추정하지 못한 카드(CREDIT_SPENDING_UNKNOWN)
     */
    record Result(List<FlowEvent> events, List<ExcludedItem> excluded) {}
}
