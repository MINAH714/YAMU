package com.yamu.be.forecast;

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
     * 선택하지 않은 계좌에 귀속되는 회차는 이 예보의 범위가 아니라 조용히 넘긴다.
     *
     * @param entries          가계부 목록 회차(확정 항목)
     * @param selectedAccounts 예보에 선택한 계좌
     * @return 입출금과 뺀 항목
     */
    static Converted convert(Long userId, List<CashflowService.Entry> entries, Set<Long> selectedAccounts,
            CardReader cards, CreditWithdrawalSchedule creditSchedule, LocalDate from, LocalDate to) {
        List<FlowEvent> events = new ArrayList<>();
        List<ExcludedItem> excluded = new ArrayList<>();
        Map<Long, Optional<CardReader.CardInfo>> cardCache = new HashMap<>();
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
                Optional<CardReader.CardInfo> card = cardCache.computeIfAbsent(item.userCardId(),
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
                date = card.get().cardType() == CardReader.CardType.CREDIT
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
