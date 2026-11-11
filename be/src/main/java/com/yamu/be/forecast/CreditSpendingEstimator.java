package com.yamu.be.forecast;

import com.yamu.be.card.UserCardRef;
import com.yamu.be.card.domain.CardIssuerStatus;
import com.yamu.be.card.domain.CardRegistrationStatus;
import com.yamu.be.card.domain.CardType;
import com.yamu.be.payment.billing.BillingCycle;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 내일부터 이번 이용기간 마감일까지 쓸 신용카드 금액을 카드마다 추정해, 그 청구서의 출금일에 보수적 선에서만 빼는 출금으로 만든다
 * (S15P31E103-286, 2026-11-06 설계 결정).
 *
 * <p>진행 중 청구서에는 오늘까지 쓴 금액만 있고 생활비는 체크카드만 보므로, 앞으로 쓸 신용카드 금액은 예보 어디에도 없었다.
 *
 * <ul>
 *   <li>대상: 출금 계좌가 선택 계좌이고, 지금 결제할 수 있는 신용카드. 사용자가 끈 카드(INACTIVE), 정지·만료 카드, 미납 청구서가 있는 카드,
 *       결제일·청구 주기를 만들 수 없는 카드는 승인이 거절돼 앞으로 쓸 금액이 없으므로 조용히 뺀다(미납은 부르는 쪽이 걸러 넘긴다).</li>
 *   <li>금액: 체크카드 생활비와 같은 규칙({@link LivingCostProjector}, S15P31E103-405, 2026-11-10 결정)으로 카드마다 내일부터
 *       이번 이용기간 마감일까지 R일의 신용 생활 소비를 예측한다. 기록 14일 이상이고 90일 안에 사용이 있으면 신용 AI
 *       ({@code POST /forecast/credit-spending}), 아니면 고정 TSB. 기록 60일 미만은 30일 점에 TSB 하한, AI 실패는 TSB. 오늘 쓴 금액은
 *       진행 중 청구서에 이미 있어 오늘 몫까지만 뺀다. 금액은 하루 값 × R이다. 마감일이 오늘이면 0일이라 넣지 않고, 0원도 넣지 않는다.</li>
 *   <li>AI 호출: 같은 R인 카드끼리 묶어 projector를 부른다(체크 생활비 코드를 바꾸지 않으려는 2026-11-11 결정). 카드 번호 대신
 *       요청 안에서만 쓰는 key를 보낸다.</li>
 *   <li>날짜: 이번 이용기간 청구서의 출금일({@link CreditWithdrawalSchedule}, 지금 결제일 기준). 예보 기간 뒤면 선에 넣지 않고
 *       기간 밖 미리보기로 따로 돌려준다(S15P31E103-348).</li>
 *   <li>신용 생활 소비 기록이 아예 없어 예측하지 못한 카드는 출금일이 예보 기간 안일 때만 {@code CREDIT_SPENDING_UNKNOWN}으로 알린다.</li>
 * </ul>
 */
final class CreditSpendingEstimator {

    private CreditSpendingEstimator() {
    }

    /**
     * @param cards        BE 1로 확인한 등록 카드 중 미납 청구서가 없는 카드. ACTIVE·신용카드·선택 계좌가 아닌 카드는 무시한다
     * @param names        카드 번호별 화면 이름(별칭, 없으면 상품명)
     * @param days         카드별 일별 신용 LIVING 결제 합계({@link CreditSpendingReader}). 어제까지 90일과 오늘을 덮어야 한다
     * @param selected     선택 계좌
     * @param today        한국 날짜 오늘(예보 시작일)
     * @param to           예보 마지막 날(포함)
     * @param schedule     신용카드 사용일 → 청구 출금일
     * @param projector    체크 생활비와 같은 규칙의 예측기(AI 서버 호출 포함)
     * @return 보수적 선 전용 출금, 알릴 항목, 예측 이력
     */
    static Result estimate(List<UserCardRef> cards, Map<Long, String> names,
            List<CreditSpendingReader.CardDailySpending> days, Set<Long> selected, LocalDate today, LocalDate to,
            CreditWithdrawalSchedule schedule, LivingCostProjector projector) {
        List<LivingCostReader.DailySpending> rows = checked(days, today);
        List<Target> targets = new ArrayList<>();
        Map<Integer, List<Long>> cardsByDays = new LinkedHashMap<>();
        for (UserCardRef card : cards) {
            if (card.cardType() != CardType.CREDIT || card.registrationStatus() != CardRegistrationStatus.ACTIVE
                    || card.issuerStatus() != CardIssuerStatus.NORMAL
                    || card.linkedAccountId() == null || !selected.contains(card.linkedAccountId())
                    || card.paymentDay() == null || !BillingCycle.supports(card.paymentDay(), card.billingCutoffDays())) {
                continue;
            }
            Optional<LocalDate> withdrawal = schedule.withdrawalDate(card, today);
            if (withdrawal.isEmpty()) {
                continue;
            }
            LocalDate periodEnd = BillingCycle.of(card.paymentDay(), card.billingCutoffDays()).cycleFor(today).periodEnd();
            int remainingDays = Math.toIntExact(ChronoUnit.DAYS.between(today, periodEnd));
            if (remainingDays <= 0) {
                continue;
            }
            targets.add(new Target(card, withdrawal.get(), periodEnd, remainingDays));
            cardsByDays.computeIfAbsent(remainingDays, key -> new ArrayList<>()).add(card.userCardId());
        }

        Map<Long, LivingCostProjector.AccountProjection> projections = new HashMap<>();
        Map<Long, String> modelVersions = new HashMap<>();
        cardsByDays.forEach((remainingDays, cardIds) -> {
            LivingCostProjector.Projection projection = projector.project(cardIds, rows, today, remainingDays,
                    AiLivingCostClient.Endpoint.CREDIT_SPENDING);
            projections.putAll(projection.byAccount());
            projection.byAccount().forEach((cardId, account) -> {
                if (LivingCostProjector.METHOD_AI.equals(account.method())) {
                    modelVersions.put(cardId, projection.aiModelVersion());
                }
            });
        });

        List<FlowEvent> events = new ArrayList<>();
        List<ExcludedItem> excluded = new ArrayList<>();
        List<FlowEvent> beyond = new ArrayList<>();
        List<Prediction> predictions = new ArrayList<>();
        for (Target target : targets) {
            UserCardRef card = target.card();
            boolean beyondHorizon = target.withdrawal().isAfter(to);
            String name = names.getOrDefault(card.userCardId(), "신용카드");
            LivingCostProjector.AccountProjection projection = projections.get(card.userCardId());
            if (projection.projectedAmount() == null) {
                // 기간 밖 출금이면 예보에 영향이 없어 알리지 않는다(기존 규칙).
                if (!beyondHorizon) {
                    excluded.add(new ExcludedItem(null, null, null, target.withdrawal(), name, null,
                            ExcludedItem.Reason.CREDIT_SPENDING_UNKNOWN));
                }
                continue;
            }
            predictions.add(new Prediction(card.userCardId(), target.remainingDays(), projection,
                    modelVersions.get(card.userCardId())));
            long amount = projection.projectedAmount();
            if (amount == 0) {
                continue;
            }
            FlowEvent event = new FlowEvent(card.linkedAccountId(), target.withdrawal(), -amount,
                    FlowEvent.Type.CARD_SPENDING_ESTIMATE, FlowEvent.Confidence.CONSERVATIVE,
                    title(name, today.plusDays(1), target.periodEnd(), target.remainingDays(), projection.dailyAmount()),
                    card.userCardId());
            (beyondHorizon ? beyond : events).add(event);
        }
        return new Result(List.copyOf(events), List.copyOf(excluded), List.copyOf(beyond), List.copyOf(predictions));
    }

    /** 예: "생활카드 · 11/7~11/11 5일 × 하루 예상 12,000원". 응답에서 추정 근거로 보인다. */
    static String title(String name, LocalDate from, LocalDate to, long days, long dailyAmount) {
        return String.format(Locale.KOREA, "%s · %d/%d~%d/%d %d일 × 하루 예상 %,d원", name, from.getMonthValue(),
                from.getDayOfMonth(), to.getMonthValue(), to.getDayOfMonth(), days, dailyAmount);
    }

    /**
     * 원천 계약(음수, 오늘 뒤 날짜, 같은 카드·날짜·출처 두 줄)을 검사하고 projector 입력 모양(카드 번호를 계좌 자리에)으로 바꾼다.
     * 예외 메시지는 전역 예외 로그에 남으므로 카드·날짜·금액을 넣지 않는다.
     */
    private static List<LivingCostReader.DailySpending> checked(List<CreditSpendingReader.CardDailySpending> days,
            LocalDate today) {
        Set<String> seen = new HashSet<>();
        List<LivingCostReader.DailySpending> rows = new ArrayList<>(days.size());
        for (CreditSpendingReader.CardDailySpending day : days) {
            if (day.amount() < 0) {
                throw new IllegalStateException("신용 생활 소비 원천이 음수 합계를 줬다.");
            }
            if (day.date().isAfter(today)) {
                throw new IllegalStateException("신용 생활 소비 원천이 오늘보다 뒤 날짜의 줄을 줬다.");
            }
            if (!seen.add(day.userCardId() + ":" + day.date() + ":" + day.origin())) {
                throw new IllegalStateException("신용 생활 소비 원천이 같은 카드·날짜·출처의 줄을 두 번 줬다.");
            }
            rows.add(new LivingCostReader.DailySpending(day.userCardId(), day.date(), day.amount(), day.amount(),
                    day.origin(), true));
        }
        return rows;
    }

    private record Target(UserCardRef card, LocalDate withdrawal, LocalDate periodEnd, int remainingDays) {}

    /**
     * 카드 하나의 예측. 예측 이력({@link CreditSpendingPredictionLog})에 남긴다.
     *
     * @param remainingDays 예측 기간 R(내일부터 이번 이용기간 마감일까지)
     * @param projection    R일 예측(하루 값 × R, 방식·신뢰도·출처)
     * @param modelVersion  AI(ML_V1)로 예측했으면 AI 서버가 답한 모델 버전, TSB면 null(-405 Codex 검토: 모델을 바꿔도 이력을 구별)
     */
    record Prediction(long userCardId, int remainingDays, LivingCostProjector.AccountProjection projection,
            String modelVersion) {}

    /**
     * @param events      카드 출금 계좌·출금일의 보수적 선 전용 출금(CARD_SPENDING_ESTIMATE, CONSERVATIVE). refId는 카드 번호
     * @param excluded    신용 생활 소비 기록이 없어 추정하지 못한 카드(CREDIT_SPENDING_UNKNOWN)
     * @param beyond      출금일이 예보 기간 뒤인 추정. 선에는 넣지 않고 기간 밖 청구 미리보기(beyondHorizon)로만 보인다(S15P31E103-348)
     * @param predictions 예측한 카드마다 하나(기간 밖·0원 포함, 기록 없는 카드 제외)
     */
    record Result(List<FlowEvent> events, List<ExcludedItem> excluded, List<FlowEvent> beyond,
            List<Prediction> predictions) {}
}
