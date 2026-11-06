package com.yamu.be.cashflow;

import com.yamu.be.cashflow.CashflowService.MatchTarget;
import com.yamu.be.cashflow.PaymentMatchResponse.Candidate;
import com.yamu.be.cashflow.PaymentMatchResponse.Candidates;
import com.yamu.be.cashflow.PaymentMatchResponse.Confirmed;
import com.yamu.be.cashflow.PaymentMatchResponse.ForecastTreatment;
import com.yamu.be.cashflow.PaymentMatchResponse.MatchStatus;
import com.yamu.be.cashflow.PaymentMatchResponse.MatchView;
import com.yamu.be.cashflow.PaymentMatchResponse.Reason;
import com.yamu.be.cashflow.PaymentMatchResponse.Status;
import com.yamu.be.cashflow.PaymentMatchResponse.Warning;
import com.yamu.be.cashflow.PaymentMatchStore.Match;
import com.yamu.be.cashflow.PaymentMatchStore.Payment;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.payment.domain.PaymentStatus;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 예정 지출 회차와 실제 YAMU 결제를 사용자가 확인해 연결한다. 연결된 회차는 잔고예보의 예정 차감에서 빠지고,
 * 연결된 결제는 생활비 추정에서 빠져 같은 지출을 두 번 빼지 않는다.
 *
 * <p>회차 하나에 결제 하나만 연결한다(DB 유일 키 두 개). 매주 반복 지출은 회차마다 따로 연결해야 한다.
 */
@Service
public class PaymentMatchService {
    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
    /** 후보로 보는 승인일 범위(예정일 앞뒤 일수, 한국 날짜). 이보다 멀면 확정 때 DATE_FAR로 알린다. */
    static final int WINDOW_DAYS = 7;
    /** 이 일수 안이면 DATE_NEAR 근거를 붙인다. */
    static final int NEAR_DAYS = 3;
    static final int CANDIDATE_LIMIT = 10;

    private final CashflowService cashflows;
    private final PaymentMatchStore store;
    private final Clock clock;

    PaymentMatchService(CashflowService cashflows, PaymentMatchStore store, Clock clock) {
        this.cashflows = cashflows;
        this.store = store;
        this.clock = clock;
    }

    /**
     * 회차와 결제를 연결한다. 같은 쌍이 이미 연결돼 있으면 새로 만들지 않고 기존 연결을 돌려준다(created=false).
     * 회차를 시리즈 잠금으로 차례로 처리하고, 잠금을 기다린 뒤 앞 요청이 커밋한 연결을 보도록 READ COMMITTED로 연다.
     * 없거나 다른 사용자의 회차·결제는 404, 연결할 수 없는 회차·결제는 400, 어느 한쪽이 다른 상대와 연결돼 있으면 409다.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Result confirm(Long authenticatedUserId, Long cashflowItemId, LocalDate occurrenceDate, Long paymentId) {
        CashflowService.requireUser(authenticatedUserId);
        MatchTarget target = cashflows.lockOccurrenceForMatch(authenticatedUserId, cashflowItemId, occurrenceDate);
        Payment payment = store.payment(authenticatedUserId, paymentId).orElseThrow(CashflowService::notFound);

        Match existing = store.byOccurrence(target.occurrenceId()).orElse(null);
        if (existing != null && existing.paymentId() == paymentId) {
            return new Result(false, confirmed(target, payment, existing));
        }
        // 시드 결제는 앱에서 일어난 결제가 아니고, 승인 전·취소된 결제는 실제 지출이 아니다.
        if (!"YAMU".equals(payment.source()) || payment.status() != PaymentStatus.APPROVED) {
            throw CashflowService.invalid("paymentId", "승인된 결제만 예정 지출과 연결할 수 있습니다.");
        }
        if (existing != null || store.byPayment(paymentId).isPresent()) {
            throw new BusinessException(ErrorCode.CASHFLOW_MATCH_CONFLICT);
        }
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        long matchId;
        try {
            matchId = store.insert(authenticatedUserId, target.occurrenceId(), paymentId, now);
        } catch (DuplicateKeyException e) {
            // 결제는 잠그지 않는다. 다른 시리즈의 회차가 같은 결제를 동시에 연결하면 유일 키가 늦은 쪽을 막는다.
            throw new BusinessException(ErrorCode.CASHFLOW_MATCH_CONFLICT);
        }
        return new Result(true, confirmed(target, payment, new Match(matchId, target.occurrenceId(), paymentId, now)));
    }

    /** 사용자의 연결을 지운다. 없거나 다른 사용자의 연결이면 404다. 지우면 회차는 다시 예정으로 세고 고칠 수 있다. */
    @Transactional
    public void delete(Long authenticatedUserId, Long matchId) {
        CashflowService.requireUser(authenticatedUserId);
        if (matchId == null || store.delete(authenticatedUserId, matchId) == 0) {
            throw CashflowService.notFound();
        }
    }

    /** 회차의 연결 상태. 연결이 없으면 match가 null이다. 없거나 다른 사용자의 회차는 404다. */
    @Transactional(readOnly = true)
    public Status status(Long authenticatedUserId, Long cashflowItemId, LocalDate occurrenceDate) {
        CashflowService.requireUser(authenticatedUserId);
        MatchTarget target = cashflows.readOccurrenceForMatch(authenticatedUserId, cashflowItemId, occurrenceDate,
                false);
        Match match = target.occurrenceId() == null ? null : store.byOccurrence(target.occurrenceId()).orElse(null);
        if (match == null) {
            return new Status(cashflowItemId, occurrenceDate, null);
        }
        Payment payment = store.payment(authenticatedUserId, match.paymentId()).orElseThrow(() ->
                new IllegalStateException("연결된 결제가 같은 사용자의 것이 아니다: match " + match.matchId()));
        return new Status(cashflowItemId, occurrenceDate, new MatchView(match.matchId(), payment.paymentId(),
                payment.merchantName(), payment.approvedAmount(), payment.status(), MatchStatus.of(payment.status()),
                match.matchedAtUtc()));
    }

    /**
     * 회차와 연결할 만한 결제 후보. 예정일 앞뒤 {@value #WINDOW_DAYS}일(한국 날짜) 안에 승인된, 아직 연결되지 않은
     * YAMU 결제를 금액 일치 3점 + 카드 일치 2점 + 업종 일치 1점 순으로, 같으면 예정일에 가까운 순·결제 ID 순으로
     * 최대 {@value #CANDIDATE_LIMIT}건 돌려준다. 저장하지 않는다.
     */
    @Transactional(readOnly = true)
    public Candidates candidates(Long authenticatedUserId, Long cashflowItemId, LocalDate occurrenceDate) {
        CashflowService.requireUser(authenticatedUserId);
        MatchTarget target = cashflows.readOccurrenceForMatch(authenticatedUserId, cashflowItemId, occurrenceDate,
                true);
        LocalDate scheduled = target.scheduledDate();
        List<Payment> payments = store.unmatchedApprovedBetween(authenticatedUserId,
                utcStartOf(scheduled.minusDays(WINDOW_DAYS)), utcStartOf(scheduled.plusDays(WINDOW_DAYS + 1)));
        List<Candidate> candidates = payments.stream()
                .sorted(Comparator.comparingInt((Payment payment) -> -score(target, payment))
                        .thenComparingLong(payment -> Math.abs(daysApart(target, payment)))
                        .thenComparingLong(Payment::paymentId))
                .limit(CANDIDATE_LIMIT)
                .map(payment -> new Candidate(payment.paymentId(), payment.merchantName(), payment.approvedAmount(),
                        payment.approvedAtUtc(), payment.userCardId(), payment.cardType(), reasons(target, payment),
                        warnings(target, payment)))
                .toList();
        return new Candidates(cashflowItemId, occurrenceDate, scheduled, target.amount(), candidates);
    }

    private static Confirmed confirmed(MatchTarget target, Payment payment, Match match) {
        return new Confirmed(match.matchId(), target.cashflowItemId(), target.occurrenceDate(), payment.paymentId(),
                MatchStatus.of(payment.status()), match.matchedAtUtc(), warnings(target, payment),
                ForecastTreatment.ACTUAL_PAYMENT_ONLY);
    }

    private static int score(MatchTarget target, Payment payment) {
        return (sameAmount(target, payment) ? 3 : 0) + (sameCard(target, payment) ? 2 : 0)
                + (sameCategory(target, payment) ? 1 : 0);
    }

    private static List<Reason> reasons(MatchTarget target, Payment payment) {
        List<Reason> reasons = new ArrayList<>();
        if (sameAmount(target, payment)) {
            reasons.add(Reason.AMOUNT_MATCH);
        }
        if (sameCard(target, payment)) {
            reasons.add(Reason.CARD_MATCH);
        }
        if (sameCategory(target, payment)) {
            reasons.add(Reason.MERCHANT_CATEGORY_MATCH);
        }
        if (payment.approvedAtUtc() != null && Math.abs(daysApart(target, payment)) <= NEAR_DAYS) {
            reasons.add(Reason.DATE_NEAR);
        }
        return reasons;
    }

    /** 업종 차이는 회차에 업종을 지정했을 때만 알린다. */
    private static List<Warning> warnings(MatchTarget target, Payment payment) {
        List<Warning> warnings = new ArrayList<>();
        if (!sameAmount(target, payment)) {
            warnings.add(Warning.AMOUNT_MISMATCH);
        }
        if (!sameCard(target, payment)) {
            warnings.add(Warning.CARD_MISMATCH);
        }
        if (payment.approvedAtUtc() != null && Math.abs(daysApart(target, payment)) > WINDOW_DAYS) {
            warnings.add(Warning.DATE_FAR);
        }
        if (target.merchantCategoryCode() != null && !sameCategory(target, payment)) {
            warnings.add(Warning.MERCHANT_CATEGORY_MISMATCH);
        }
        return warnings;
    }

    private static boolean sameAmount(MatchTarget target, Payment payment) {
        return Objects.equals(target.amount(), payment.approvedAmount());
    }

    private static boolean sameCard(MatchTarget target, Payment payment) {
        return Objects.equals(target.userCardId(), payment.userCardId());
    }

    private static boolean sameCategory(MatchTarget target, Payment payment) {
        return target.merchantCategoryCode() != null
                && target.merchantCategoryCode().equals(payment.merchantCategoryCode());
    }

    /** 예정일에서 결제 승인일(한국 날짜)까지의 일수. 결제가 늦으면 양수다. */
    private static long daysApart(MatchTarget target, Payment payment) {
        LocalDate approved = payment.approvedAtUtc().atOffset(ZoneOffset.UTC).atZoneSameInstant(KOREA).toLocalDate();
        return ChronoUnit.DAYS.between(target.scheduledDate(), approved);
    }

    private static LocalDateTime utcStartOf(LocalDate koreanDate) {
        return koreanDate.atStartOfDay(KOREA).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    /** created는 새로 연결했으면 true(201), 같은 쌍이 이미 있었으면 false(200)다. */
    public record Result(boolean created, Confirmed body) {
    }
}
