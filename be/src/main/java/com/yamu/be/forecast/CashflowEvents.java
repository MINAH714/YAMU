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
     * 카드사 정지·만료 카드로는 결제가 일어날 수 없어 그 카드의 예정 지출은 빼고 알린다. 정상 카드였어도 기간 밖으로 출금됐을 회차는
     * 알리지 않는다. 사용자가 끈 INACTIVE 카드·미납 청구서가 있는 신용카드는 승인이 거절되지만 되돌리거나 다른 카드로 내면 돈이 나가므로
     * 부족 위험을 놓치지 않게 그대로 뺀다(2026-11-08 결정). 결제일 정보가 없어 출금일을 정할 수 없는 신용카드 회차도
     * 빼고 알린다(사용일이 기간 안일 때만).
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
            boolean cardUnusable = false;
            boolean withdrawalDateUnknown = false;
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
                // 정상(NORMAL)이 아닌 상태는 모르는 값이어도 쓸 수 있는 카드로 보지 않는다.
                cardUnusable = card.get().issuerStatus() != CardIssuerStatus.NORMAL;
                if (card.get().cardType() == CardType.CREDIT) {
                    Optional<LocalDate> withdrawal = creditSchedule.withdrawalDate(card.get(), entry.date());
                    // 출금일을 모르면 사용일로 기간·계좌만 따져 알린다. 사용일보다 앞서 출금되는 일은 없다.
                    withdrawalDateUnknown = withdrawal.isEmpty();
                    date = withdrawal.orElse(entry.date());
                } else {
                    date = entry.date();
                }
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
            // 정지 카드도 선택한 계좌·기간 안의 회차만 알린다. 범위 밖 회차는 정상 카드처럼 이 예보와 관계없다.
            if (cardUnusable) {
                excluded.add(ExcludedItem.of(entry, ExcludedItem.Reason.CARD_SUSPENDED_OR_EXPIRED));
                continue;
            }
            if (withdrawalDateUnknown) {
                excluded.add(ExcludedItem.of(entry, ExcludedItem.Reason.BILLING_CYCLE_UNKNOWN));
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
