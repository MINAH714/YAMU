package com.yamu.be.performance;

import com.yamu.be.cashflow.CashflowService;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 잔고예보 예정 지출을 이 카드 기준으로 나눈다(S15P31E103-375, 2026-11-10 결정). DB 없이 입력만으로 계산한다.
 *
 * <ul>
 *   <li>대상: 지출·확인(CONFIRMED)·예정(SCHEDULED) 회차 중 금액이 있고, 오늘부터 이번 달 말일까지이며, 아직 결제와 연결되지 않은 것.
 *       목록 조회({@link CashflowService#list})가 지출·확인·예정만 펼쳐 준다.</li>
 *   <li>이 카드로 낼 회차는 월말 예상에 더한다(이 카드 실적에서 빠지는 업종은 뺀다).</li>
 *   <li>계좌나 다른 카드로 낼 회차는 항목(cashflowItemId)마다 묶어 옮길 수 있는지 판정한다. 날짜는 가장 이른 회차, 금액은 회차 합.
 *       옮기기({@code PATCH /api/cashflow-items/{id}} userCardId)는 다음 회차부터 이후 전부를 규칙 값으로 바꾸기 때문이다.</li>
 *   <li>회차마다 값이 다를 수 있다(이번 회차만 수정). 옮기기는 따로 고친 회차를 규칙 값으로 덮고 결제와 연결된 회차가 있으면 거절하므로,
 *       그런 항목은 옮길 수 없음으로 둔다(2026-11-12 결정, 리뷰 반영). 같은 항목의 회차끼리 업종·제목·금액·결제 수단이 다르거나,
 *       결제와 연결된 회차가 있거나, {@link MoveLockReader}가 찾은 잠금이 있으면 해당한다. 그래서 묶음의 값은 회차 순서와
 *       상관없이 한 가지다.</li>
 *   <li>옮길 수 없는 사유(먼저 걸린 것 하나): 이 카드가 정지·만료, 결제와 연결된 회차, 따로 고친 회차, 카드로 낼 수 없는 지출(임시 목록),
 *       이 카드 실적 제외 업종, 마감(첫 회차 2일 전)이 지남. 다른 카드에서 빼면 그 카드가 미달되는지는 보지 않는다(한계).</li>
 *   <li>금액 큰 순, 같으면 날짜·항목 번호 순.</li>
 * </ul>
 */
final class ScheduledExpenses {

    /** 결제처가 카드 변경을 반영하는 데 걸리는 날(통신사 1~2영업일). 첫 회차 이 날 수 전까지 바꿔야 한다. */
    static final int CHANGE_LEAD_DAYS = 2;

    /** 임시: 카드로 낼 수 없는 지출의 업종 코드. 지금 업종 목록(MerchantCategories)에는 없고, 앱이 보낼 수 있는 값을 대비한다. */
    static final Set<String> CARD_UNPAYABLE_CATEGORIES = Set.of("RENT", "LOAN", "CARD_BILL", "SAVINGS");
    /** 임시: 제목으로 거르는 말. 업종 목록에 없는 "월세"·"카드값" 같은 항목을 사용자가 직접 적은 경우. */
    static final List<String> CARD_UNPAYABLE_TITLE_WORDS = List.of("월세", "대출", "카드대금", "카드값", "적금", "저축");

    static final String METHOD_ACCOUNT = "ACCOUNT";
    static final String METHOD_CARD = "CARD";

    static final String REASON_MATCHED = "결제와 연결된 회차가 있어 한 번에 옮길 수 없어요";
    static final String REASON_EDITED = "따로 고친 회차가 있어 옮기면 그 수정이 사라져요";

    /** 옮기기(이후 전부 수정)가 거절되거나 사용자가 고친 값을 지우는 항목. */
    enum MoveLock { MATCHED, EDITED }

    private ScheduledExpenses() {
    }

    record Split(List<PerformanceAnalyzer.Scheduled> scheduled, List<PerformanceAnalyzer.Movable> movable) {
    }

    static Split split(List<CashflowService.Entry> entries, long userCardId, boolean cardUsable,
            Optional<PerformancePolicyCatalog.Policy> policy, LocalDate today) {
        return split(entries, userCardId, cardUsable, policy, today, Map.of());
    }

    /**
     * @param entries     오늘~말일 예정 지출 회차({@link CashflowService#list} expand)
     * @param userCardId  분석하는 카드
     * @param cardUsable  이 카드가 결제할 수 있는 상태(카드사 정지·만료가 아님)인지
     * @param policy      이 카드 상품의 실적 정책. 원본에 없는 상품이면 비어 있다
     * @param today       한국 날짜 오늘
     * @param locks       {@link MoveLockReader}가 찾은 항목별 잠금(이번 달 밖 회차·뒤 규칙 포함)
     */
    static Split split(List<CashflowService.Entry> entries, long userCardId, boolean cardUsable,
            Optional<PerformancePolicyCatalog.Policy> policy, LocalDate today, Map<Long, MoveLock> locks) {
        List<PerformanceAnalyzer.Scheduled> scheduled = new ArrayList<>();
        Map<Long, Group> groups = new LinkedHashMap<>();
        Set<Long> matched = new HashSet<>();
        for (CashflowService.Entry entry : entries) {
            CashflowService.Item item = entry.item();
            if (entry.matched() || entry.paid()) {
                matched.add(item.id());
            }
            if (entry.paid() || item.amount() == null || item.amount() <= 0 || entry.date().isBefore(today)) {
                continue;
            }
            if (item.userCardId() != null && item.userCardId() == userCardId) {
                if (policy.map(p -> !p.excludes(item.merchantCategoryCode())).orElse(true)) {
                    scheduled.add(new PerformanceAnalyzer.Scheduled(item.id(), item.title(), entry.date(), item.amount()));
                }
                continue;
            }
            groups.computeIfAbsent(item.id(), id -> new Group()).add(entry);
        }
        List<PerformanceAnalyzer.Movable> movable = groups.values().stream()
                .map(group -> group.judge(cardUsable, policy, today,
                        matched.contains(group.id()) ? MoveLock.MATCHED : locks.get(group.id())))
                .sorted(Comparator.comparingLong(PerformanceAnalyzer.Movable::amount).reversed()
                        .thenComparing(PerformanceAnalyzer.Movable::date)
                        .thenComparingLong(PerformanceAnalyzer.Movable::cashflowItemId))
                .toList();
        return new Split(List.copyOf(scheduled), movable);
    }

    /** 한 항목의 이번 달 회차들. 값은 가장 이른 회차(날짜·원래 회차 순) 것을 쓰고, 회차끼리 값이 다르면 따로 고친 항목이다. */
    private static final class Group {
        private CashflowService.Entry first;
        private long amount;
        private boolean edited;

        void add(CashflowService.Entry entry) {
            if (first != null && !sameValues(first.item(), entry.item())) {
                edited = true;
            }
            if (first == null || entry.date().isBefore(first.date())
                    || entry.date().equals(first.date()) && entry.occurrenceDate().isBefore(first.occurrenceDate())) {
                first = entry;
            }
            amount += entry.item().amount();
        }

        long id() {
            return first.item().id();
        }

        PerformanceAnalyzer.Movable judge(boolean cardUsable, Optional<PerformancePolicyCatalog.Policy> policy,
                LocalDate today, MoveLock lock) {
            CashflowService.Item item = first.item();
            String category = item.merchantCategoryCode();
            LocalDate deadline = first.date().minusDays(CHANGE_LEAD_DAYS);
            String method = item.userCardId() != null ? METHOD_CARD : METHOD_ACCOUNT;
            String reason = null;
            if (!cardUsable) {
                reason = "정지되었거나 만료된 카드로는 옮길 수 없어요";
            } else if (lock == MoveLock.MATCHED) {
                reason = REASON_MATCHED;
            } else if (edited || lock == MoveLock.EDITED) {
                reason = REASON_EDITED;
            } else if (unpayable(category, item.title())) {
                reason = "카드로 낼 수 없는 지출이에요";
            } else if (policy.map(p -> p.excludes(category)).orElse(false)) {
                reason = "이 카드 실적에서 빠지는 업종이에요";
            } else if (deadline.isBefore(today)) {
                reason = "결제일이 가까워 이번 달에는 바꾸기 어려워요";
            }
            String note = null;
            if (reason == null) {
                if (category == null) {
                    note = "카드로 낼 수 있는지 결제처에 확인해 주세요";
                } else if (policy.isEmpty() || policy.get().uncertain(category)) {
                    note = "이 카드 실적에 들어가는지 카드사 약관을 확인해 주세요";
                } else {
                    note = "결제처 반영까지 1~2일 걸려요";
                }
            }
            return new PerformanceAnalyzer.Movable(item.id(), item.title(), first.date(), amount, method,
                    reason == null, deadline, note, reason);
        }

        /** 옮기기가 규칙 값으로 덮을 때 결과가 달라지는 값(업종·제목·금액·결제 수단). 메모는 실적과 상관없다. */
        private static boolean sameValues(CashflowService.Item a, CashflowService.Item b) {
            return Objects.equals(a.merchantCategoryCode(), b.merchantCategoryCode())
                    && Objects.equals(a.title(), b.title()) && Objects.equals(a.amount(), b.amount())
                    && Objects.equals(a.accountId(), b.accountId()) && Objects.equals(a.userCardId(), b.userCardId());
        }
    }

    static boolean unpayable(String category, String title) {
        if (category != null && CARD_UNPAYABLE_CATEGORIES.contains(category)) {
            return true;
        }
        return title != null && CARD_UNPAYABLE_TITLE_WORDS.stream().anyMatch(title::contains);
    }
}
