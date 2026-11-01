package com.yamu.be.forecast;

import com.yamu.be.card.CardQueryService;
import com.yamu.be.card.UserCardRef;
import com.yamu.be.card.domain.CardIssuerStatus;
import com.yamu.be.card.domain.CardType;
import com.yamu.be.cashflow.CashflowItem;
import com.yamu.be.cashflow.CashflowService;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 가계부 예정 회차를 돈이 실제로 오가는 계좌·날짜의 입출금으로 바꾼다.
 * 한 회차는 귀속 계좌 하나에만 반영하고(FR-064), 실제 결제와 연결된 회차는 결제·청구 데이터가 대신하므로 뺀다(DEC-008).
 */
final class CashflowEvents {

    private CashflowEvents() {
    }

    /**
     * 선택한 계좌에 귀속되고 from~to에 오가는 입출금과, 계산에서 뺀 항목을 돌려준다.
     * 계좌 지정 항목은 예정일에, 체크카드는 사용일에, 신용카드는 청구 출금일에 카드 출금 계좌에서 나간다.
     * 선택하지 않은 계좌에 귀속되는 회차는 이 예보의 범위가 아니라 조용히 넘긴다. 오늘(from) 수입은 더하지 않고 알린다.
     * 카드사 정지·만료 카드로는 결제가 일어날 수 없어 그 카드의 예정 지출은 빼고 알린다. 추천에서만 뺀 INACTIVE 카드는 그대로 뺀다.
     * 이미 확정된 카드 청구는 카드가 정지돼도 출금되므로 이 판단과 상관없다(청구는 {@link CardBillReader}).
     *
     * @param entries          가계부 목록 회차(확정 항목)
     * @param selectedAccounts 예보에 선택한 계좌
     * @param cards            BE 1 카드 조회. 없거나 해제된 카드는 empty다
     * @return 입출금과 뺀 항목
     */
    static Converted convert(Long userId, List<CashflowService.Entry> entries, Set<Long> selectedAccounts,
            CardQueryService cards, CreditWithdrawalSchedule creditSchedule, LocalDate from, LocalDate to) {
        List<FlowEvent> events = new ArrayList<>();
        List<ExcludedItem> excluded = new ArrayList<>();
        Map<Long, Optional<UserCardRef>> cardCache = new HashMap<>();
        for (CashflowService.Entry entry : entries) {
            if (entry.paid()) {
                continue;
            }
            CashflowService.Item item = entry.item();
            Long accountId;
            LocalDate date;
            if (item.accountId() != null) {
                accountId = item.accountId();
                date = entry.date();
            } else if (item.userCardId() != null) {
                Optional<UserCardRef> card = cardCache.computeIfAbsent(item.userCardId(),
                        cardId -> cards.findRegisteredCard(userId, cardId));
                if (card.isEmpty()) {
                    excluded.add(ExcludedItem.of(entry, ExcludedItem.Reason.CARD_UNAVAILABLE));
                    continue;
                }
                if (card.get().linkedAccountId() == null) {
                    excluded.add(ExcludedItem.of(entry, ExcludedItem.Reason.NO_WITHDRAWAL_ACCOUNT));
                    continue;
                }
                accountId = card.get().linkedAccountId();
                // 선택하지 않은 계좌의 카드는 정지 여부와 상관없이 이 예보의 범위가 아니라 알리지 않는다.
                if (!selectedAccounts.contains(accountId)) {
                    continue;
                }
                // 정상(NORMAL)이 아닌 상태는 모르는 값이어도 쓸 수 있는 카드로 보지 않는다.
                if (card.get().issuerStatus() != CardIssuerStatus.NORMAL) {
                    excluded.add(ExcludedItem.of(entry, ExcludedItem.Reason.CARD_SUSPENDED_OR_EXPIRED));
                    continue;
                }
                date = card.get().cardType() == CardType.CREDIT
                        ? creditSchedule.withdrawalDate(card.get(), entry.date()) : entry.date();
            } else {
                // 등록 API는 계좌·카드 중 하나를 받지만, 다른 경로로 들어온 행이 있으면 어느 계좌에도 임의로 배분하지 않는다.
                excluded.add(ExcludedItem.of(entry, ExcludedItem.Reason.NO_WITHDRAWAL_ACCOUNT));
                continue;
            }
            if (!selectedAccounts.contains(accountId)) {
                continue;
            }
            // 기간 밖으로 미뤄진 신용카드 출금은 이번 예보에 들어오지 않는다.
            if (date.isBefore(from) || date.isAfter(to)) {
                continue;
            }
            // 금액 미정은 0원 지출이 아니므로 차감하지 않고 알린다.
            if (item.amount() == null) {
                excluded.add(ExcludedItem.of(entry, ExcludedItem.Reason.AMOUNT_UNKNOWN));
                continue;
            }
            boolean income = item.direction() == CashflowItem.Direction.INCOME;
            // from은 현재 잔액을 읽은 날(오늘)이다. 오늘 수입은 이미 잔액에 들어왔을 수 있어 더하면 잔액을 부풀려 부족 위험을 가린다.
            // 오늘 지출은 이미 빠졌어도 잔액을 적게 볼 뿐이라 그대로 뺀다.
            if (income && date.equals(from)) {
                excluded.add(ExcludedItem.of(entry, ExcludedItem.Reason.TODAY_INCOME_UNVERIFIED));
                continue;
            }
            events.add(new FlowEvent(accountId, date, income ? item.amount() : -item.amount(),
                    income ? FlowEvent.Type.SCHEDULED_INCOME : FlowEvent.Type.SCHEDULED_OUTFLOW,
                    FlowEvent.Confidence.CONFIRMED, item.title(), item.id()));
        }
        return new Converted(List.copyOf(events), List.copyOf(excluded));
    }

    /**
     * 변환 결과.
     *
     * @param events   예보에 반영할 입출금
     * @param excluded 계산에서 뺀 항목과 이유
     */
    record Converted(List<FlowEvent> events, List<ExcludedItem> excluded) {}
}
