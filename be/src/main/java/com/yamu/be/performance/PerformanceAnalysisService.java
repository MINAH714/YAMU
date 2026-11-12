package com.yamu.be.performance;

import com.yamu.be.card.CardActivityReader;
import com.yamu.be.card.domain.CardIssuerStatus;
import com.yamu.be.card.domain.CardProduct;
import com.yamu.be.card.domain.CardProductRepository;
import com.yamu.be.card.domain.UserCard;
import com.yamu.be.card.domain.UserCardRepository;
import com.yamu.be.cashflow.CashflowItem;
import com.yamu.be.cashflow.CashflowService;
import com.yamu.be.global.common.ApiResponse;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 실적 달성 유불리 분석(S15P31E103-375). 판정 규칙은 {@link PerformanceAnalyzer}, 예정 지출 나누기는 {@link ScheduledExpenses}.
 *
 * <ul>
 *   <li>없는·남의·등록 전·등록 해제 카드는 404 RESOURCE_NOT_FOUND(실적 상세와 같음).</li>
 *   <li>422 DATA_INSUFFICIENT: 목표 실적이 없는 카드(실적 조건 없음), 이번 달 실적 행이 없는 카드, 이미 달성하지 않았는데 최근
 *       3개월 실적 행이 하나도 없는 카드.</li>
 *   <li>scenarioMoveItemIds는 이 카드로 옮길 수 있는(eligible) 예정 지출만 받는다. 남의 항목·옮길 수 없는 항목은 400이다.
 *       가정만 해서 다시 계산하고 아무것도 저장하지 않는다.</li>
 * </ul>
 */
@Service
public class PerformanceAnalysisService {

    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    static final String METHOD = "SCHEDULED_PLUS_AVG_3M";
    /** what-if로 한 번에 가정할 수 있는 예정 지출 수. */
    static final int MAX_SCENARIO_ITEMS = 20;

    private final UserCardRepository cards;
    private final CardProductRepository products;
    private final CardActivityReader activity;
    private final PerformanceTargets targets;
    private final PerformanceHistoryReader history;
    private final PerformancePolicyCatalog policies;
    private final CashflowService cashflow;
    private final MoveLockReader moveLocks;
    private final PerformanceDecisionRepository decisions;
    private final Clock clock;

    PerformanceAnalysisService(UserCardRepository cards, CardProductRepository products, CardActivityReader activity,
            PerformanceTargets targets, PerformanceHistoryReader history, PerformancePolicyCatalog policies,
            CashflowService cashflow, MoveLockReader moveLocks, PerformanceDecisionRepository decisions, Clock clock) {
        this.cards = cards;
        this.products = products;
        this.activity = activity;
        this.targets = targets;
        this.history = history;
        this.policies = policies;
        this.cashflow = cashflow;
        this.moveLocks = moveLocks;
        this.decisions = decisions;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PerformanceAnalysisResponse analyze(long userId, long userCardId, List<Long> scenarioItemIds) {
        UserCard card = registeredCard(userId, userCardId);
        Calculation calc = calculate(userId, card, Set.copyOf(scenarioItemIds), null);
        LocalDate today = calc.today();
        PerformanceAnalyzer.Result r = calc.result();
        YearMonth month = YearMonth.from(today);

        PerformanceAnalysisResponse.Decision decision = decisions
                .findByUserIdAndUserCardIdAndPerformanceMonth(userId, userCardId, month.atDay(1))
                .map(PerformanceAnalysisService::decisionOf)
                .orElse(null);
        PerformanceAnalysisResponse.Scenario scenario = scenarioItemIds.isEmpty() ? null
                : new PerformanceAnalysisResponse.Scenario(List.copyOf(scenarioItemIds), r.scenarioMovedTotal(),
                        r.projected(), r.guide());

        return new PerformanceAnalysisResponse(card.getId(), month.toString(),
                LocalDateTime.now(clock.withZone(ZoneOffset.UTC)).truncatedTo(ChronoUnit.SECONDS),
                calc.target().amount(), calc.recognized(), r.remaining(),
                today.lengthOfMonth() - today.getDayOfMonth(),
                new PerformanceAnalysisResponse.Projection(r.scheduledTotal(), r.natural(), r.averageDaily(),
                        r.projected(), r.likelihood().name(), r.shortfallIfTrend(), calc.historyMonths(), METHOD),
                r.guide(), r.headline(), r.message(),
                r.benefitsAtStake().stream().map(n -> new PerformanceAnalysisResponse.BenefitAtStake(n, null)).toList(),
                true,
                r.steps().stream().map(s -> new PerformanceAnalysisResponse.Step(s.no(), s.label(), s.amount(),
                        s.note())).toList(),
                r.movable().stream().map(m -> new PerformanceAnalysisResponse.MovableItem(m.cashflowItemId(),
                        m.title(), m.date(), m.amount(), m.currentMethod(), m.eligible(),
                        m.eligible() ? m.amount() : null, m.deadline(), m.note(), m.reason())).toList(),
                r.shortfall(), r.extraBenefit(), r.lossIfForced(), decision, scenario);
    }

    /**
     * 판단 기록 때 저장할 지금 판정. 판정할 수 없는 카드(422가 날 상황)면 null.
     */
    PerformanceGuide currentGuide(long userId, UserCard card) {
        return guideFor(userId, card, null).orElse(null);
    }

    /**
     * 카드별 실적 목록(S15P31E103-373)에 보여 줄 이번 달 판정. 분석 API와 같은 계산이다. 판정할 수 없는 카드(분석이 422를 낼
     * 상황)면 비어 있다.
     *
     * @param expenses {@link #scheduledExpenses}로 미리 읽은 이번 달 남은 예정 지출. 카드마다 다시 읽지 않게 받는다
     */
    Optional<PerformanceGuide> guideFor(long userId, UserCard card, Expenses expenses) {
        try {
            return Optional.of(calculate(userId, card, Set.of(), expenses).result().guide());
        } catch (BusinessException e) {
            if (e.getErrorCode() == ErrorCode.DATA_INSUFFICIENT) {
                return Optional.empty();
            }
            throw e;
        }
    }

    /** 오늘~말일 확인된 지출 예정(반복 펼침)과 옮기기 잠금. 사용자의 모든 카드·계좌 것이다. */
    Expenses scheduledExpenses(long userId) {
        LocalDate today = today();
        List<CashflowService.Entry> entries = cashflow.list(userId, today, YearMonth.from(today).atEndOfMonth(),
                CashflowItem.Direction.EXPENSE, CashflowItem.Status.CONFIRMED, true);
        Set<Long> itemIds = new LinkedHashSet<>();
        entries.forEach(e -> itemIds.add(e.item().id()));
        return new Expenses(entries, moveLocks.locks(userId, itemIds, today));
    }

    /** 이번 달 남은 예정 지출 회차와 항목별 옮기기 잠금({@link MoveLockReader}). */
    record Expenses(List<CashflowService.Entry> entries, Map<Long, ScheduledExpenses.MoveLock> locks) {
    }

    /** 응답의 판단 기록. 되돌린 기록(decision NULL)이면 null. */
    static PerformanceAnalysisResponse.Decision decisionOf(PerformanceDecision row) {
        return row.getDecision() == null ? null : new PerformanceAnalysisResponse.Decision(row.getDecision(),
                row.getDecidedAt(), row.getAnalysisVersion(), row.isNotificationsMuted());
    }

    /** 없는·남의·등록 전·등록 해제 카드는 404. */
    UserCard registeredCard(long userId, long userCardId) {
        return cards.findByIdAndUserId(userCardId, userId)
                .filter(c -> c.getRegistrationStatus().isRegistered())
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    LocalDate today() {
        return LocalDate.now(clock.withZone(KST));
    }

    /** @param expenses 미리 읽은 남은 예정 지출. null이면 여기서 읽는다 */
    private Calculation calculate(long userId, UserCard card, Set<Long> scenarioItemIds,
            Expenses expenses) {
        LocalDate today = today();
        YearMonth month = YearMonth.from(today);
        CardProduct product = products.findById(card.getCardProductId())
                .orElseThrow(() -> new IllegalStateException("카드 상품 없음: " + card.getCardProductId()));
        Long recognizedRow = activity.recognizedAmounts(List.of(card.getId()), month).get(card.getId());
        long recognized = recognizedRow == null ? 0 : recognizedRow;
        PerformanceAnalyzer.Target target = targets.targetFor(product.getId(), recognized, today)
                .orElseThrow(() -> new BusinessException(ErrorCode.DATA_INSUFFICIENT));
        if (recognizedRow == null) {
            throw new BusinessException(ErrorCode.DATA_INSUFFICIENT);
        }
        List<PerformanceAnalyzer.MonthSample> samples = recognized >= target.amount() ? List.of()
                : history.samePeriod(userId, card.getId(), today);
        if (recognized < target.amount() && samples.isEmpty()) {
            throw new BusinessException(ErrorCode.DATA_INSUFFICIENT);
        }

        Optional<PerformancePolicyCatalog.Policy> policy = policies.find(product.getIssuerCode(),
                product.getProductName(), product.getCardType().name());
        Expenses read = expenses != null ? expenses : scheduledExpenses(userId);
        ScheduledExpenses.Split split = ScheduledExpenses.split(read.entries(), card.getId(),
                card.getIssuerStatus() == CardIssuerStatus.NORMAL, policy, today, read.locks());
        requireMovable(scenarioItemIds, split.movable());

        PerformanceAnalyzer.Result result = PerformanceAnalyzer.analyze(new PerformanceAnalyzer.Input(today, target,
                recognized, samples, split.scheduled(), split.movable(), scenarioItemIds));
        return new Calculation(today, target, recognized, samples.size(), result);
    }

    private static void requireMovable(Set<Long> scenarioItemIds, List<PerformanceAnalyzer.Movable> movable) {
        Set<Long> eligible = new LinkedHashSet<>();
        movable.stream().filter(PerformanceAnalyzer.Movable::eligible)
                .forEach(m -> eligible.add(m.cashflowItemId()));
        if (!eligible.containsAll(scenarioItemIds)) {
            throw invalidScenario("이 카드로 옮길 수 있는 예정 지출만 가정할 수 있습니다.");
        }
    }

    /**
     * "1,2,3"을 예정 지출 번호 목록으로 바꾼다. null·빈 값은 가정 없음. 같은 번호는 한 번만 센다.
     * 숫자가 아니거나 0 이하이거나 {@value #MAX_SCENARIO_ITEMS}개를 넘으면 400.
     */
    static List<Long> parseScenario(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        Set<Long> ids = new LinkedHashSet<>();
        for (String part : raw.split(",", -1)) {
            String text = part.strip();
            long id;
            try {
                id = Long.parseLong(text);
            } catch (NumberFormatException e) {
                throw invalidScenario("예정 지출 번호를 쉼표로 구분해 보내 주세요.");
            }
            if (id <= 0) {
                throw invalidScenario("예정 지출 번호를 쉼표로 구분해 보내 주세요.");
            }
            ids.add(id);
        }
        if (ids.size() > MAX_SCENARIO_ITEMS) {
            throw invalidScenario("예정 지출은 한 번에 " + MAX_SCENARIO_ITEMS + "개까지 가정할 수 있습니다.");
        }
        return List.copyOf(ids);
    }

    private static BusinessException invalidScenario(String reason) {
        return new BusinessException(ErrorCode.INVALID_REQUEST, Map.of("fieldErrors",
                List.of(new ApiResponse.FieldErrorResponse("scenarioMoveItemIds", reason))));
    }

    private record Calculation(LocalDate today, PerformanceAnalyzer.Target target, long recognized,
            int historyMonths, PerformanceAnalyzer.Result result) {
    }
}
