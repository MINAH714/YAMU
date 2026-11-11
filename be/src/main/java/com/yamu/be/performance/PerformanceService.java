package com.yamu.be.performance;

import com.yamu.be.card.CardActivityReader;
import com.yamu.be.card.CardBenefitSummaryReader;
import com.yamu.be.card.CardIssuers;
import com.yamu.be.card.domain.CardProduct;
import com.yamu.be.card.domain.CardProductRepository;
import com.yamu.be.card.domain.CardRegistrationStatus;
import com.yamu.be.card.domain.UserCard;
import com.yamu.be.card.domain.UserCardRepository;
import com.yamu.be.global.common.ApiResponse;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.performance.CardPerformancesResponse.RecentChange;
import java.text.NumberFormat;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 카드별 실적 현황·상세(S15P31E103-373). 실적은 결제 승인 때 쌓인 {@code card_monthly_performance}를 읽고, 목표 실적·구간은
 * 카드 목록과 같은 {@link CardBenefitSummaryReader}(BE1 -308 임시 규칙)를 쓴다. 상태 판정 규칙은 {@link PerformanceStatus}.
 *
 * <p>카드의 실적은 다음 달 혜택의 "전월 실적"이 된다. 그래서 목표·구간은 오늘 유효한 혜택 정책 기준이다(지난 달을 볼 때도
 * 그때의 정책 버전을 다시 고르지 않는다).
 */
@Service
public class PerformanceService {

    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    /** 이번 달 이 날까지는 추세를 보지 않고 ON_TRACK(2026-11-10 결정). */
    static final int TREND_GRACE_DAYS = 5;
    /** 받는 월의 범위(임시, 리포트 S15P31E103-372와 같음). */
    static final YearMonth MIN_MONTH = YearMonth.of(2000, 1);
    static final YearMonth MAX_MONTH = YearMonth.of(2100, 12);
    private static final Set<CardRegistrationStatus> REGISTERED =
            EnumSet.of(CardRegistrationStatus.ACTIVE, CardRegistrationStatus.INACTIVE);

    private final UserCardRepository cards;
    private final CardProductRepository products;
    private final CardActivityReader activity;
    private final CardBenefitSummaryReader benefitSummaries;
    private final PerformanceReader reader;
    private final Clock clock;

    PerformanceService(UserCardRepository cards, CardProductRepository products, CardActivityReader activity,
            CardBenefitSummaryReader benefitSummaries, PerformanceReader reader, Clock clock) {
        this.cards = cards;
        this.products = products;
        this.activity = activity;
        this.benefitSummaries = benefitSummaries;
        this.reader = reader;
        this.clock = clock;
    }

    /** @param yearMonth 한국 기준 실적 월. null이면 이번 달 */
    @Transactional(readOnly = true)
    public CardPerformancesResponse overview(long userId, YearMonth yearMonth) {
        LocalDate today = LocalDate.now(clock.withZone(KST));
        YearMonth month = yearMonth != null ? checked(yearMonth) : YearMonth.from(today);
        List<UserCard> owned = cards.findOrdered(userId, REGISTERED);
        Map<Long, CardProduct> productById = productsOf(owned);
        List<Long> ids = owned.stream().map(UserCard::getId).toList();
        Map<Long, Long> recognized = activity.recognizedAmounts(ids, month);
        Set<Long> provisional = reader.cardsWithUnconfirmed(userId, ids, month);
        Map<Long, RecentChange> recent = reader.recentChanges(userId, ids, month);
        Map<Long, CardBenefitSummaryReader.Summary> summaries = benefitSummaries.summaries(productById.keySet(), today);

        List<CardPerformancesResponse.Item> items = owned.stream().map(card -> {
            CardProduct product = productById.get(card.getCardProductId());
            CardIssuers.Issuer issuer = CardIssuers.of(product.getIssuerCode());
            Progress progress = progress(summaries.get(product.getId()), recognized.get(card.getId()), month, today);
            return new CardPerformancesResponse.Item(card.getId(), nickname(card, product), issuer, issuer.name(),
                    product.getCardType().name(), progress.target(), label(progress.target()), progress.recognized(),
                    progress.remaining(), progress.status(), null, null, provisional.contains(card.getId()),
                    recent.get(card.getId()));
        }).toList();
        return new CardPerformancesResponse(month.toString(), daysLeft(month, today), items, null);
    }

    /** 이번 달 실적 상세. 없는·남의·등록 전·등록 해제 카드는 404 RESOURCE_NOT_FOUND. */
    @Transactional(readOnly = true)
    public PerformanceDetailResponse detail(long userId, long userCardId) {
        UserCard card = cards.findByIdAndUserId(userCardId, userId)
                .filter(c -> c.getRegistrationStatus().isRegistered())
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        LocalDate today = LocalDate.now(clock.withZone(KST));
        YearMonth month = YearMonth.from(today);
        CardProduct product = products.findById(card.getCardProductId())
                .orElseThrow(() -> new IllegalStateException("카드 상품 없음: " + card.getCardProductId()));
        Long recognizedRow = activity.recognizedAmounts(List.of(card.getId()), month).get(card.getId());
        CardBenefitSummaryReader.Summary summary =
                benefitSummaries.summaries(List.of(product.getId()), today).get(product.getId());
        Progress progress = progress(summary, recognizedRow, month, today);

        List<PerformanceDetailResponse.Tier> tiers = summary == null ? List.of() : summary.tiers().stream()
                .map(t -> new PerformanceDetailResponse.Tier(t.threshold(), label(t.threshold()),
                        t.benefitNames().isEmpty() ? null : String.join(", ", t.benefitNames()), t.monthlyKrwLimit(),
                        recognizedRow != null && recognizedRow >= t.threshold()))
                .toList();
        return new PerformanceDetailResponse(card.getId(), month.toString(),
                new PerformanceDetailResponse.Summary(progress.target(), progress.recognized(), progress.remaining(),
                        progress.status()),
                tiers, nextMonthImpact(progress, tiers), null);
    }

    /**
     * 목표·남은 금액·상태. 판정 순서는 {@link PerformanceStatus}의 선언 순서.
     *
     * @param recognizedRow 그달 실적 행의 값. 행이 없으면 null
     */
    static Progress progress(CardBenefitSummaryReader.Summary summary, Long recognizedRow, YearMonth month,
            LocalDate today) {
        long recognized = recognizedRow == null ? 0 : recognizedRow;
        Long target = summary == null ? null : summary.targetFor(recognized);
        if (target == null) {
            return new Progress(null, recognized, 0, PerformanceStatus.NO_TARGET);
        }
        long remaining = Math.max(0, target - recognized);
        if (recognizedRow == null) {
            return new Progress(target, recognized, remaining, PerformanceStatus.INSUFFICIENT_DATA);
        }
        if (recognized >= target) {
            return new Progress(target, recognized, 0, PerformanceStatus.ACHIEVED);
        }
        YearMonth current = YearMonth.from(today);
        if (month.isBefore(current)) {
            return new Progress(target, recognized, remaining, PerformanceStatus.AT_RISK);
        }
        if (month.isAfter(current) || today.getDayOfMonth() <= TREND_GRACE_DAYS) {
            return new Progress(target, recognized, remaining, PerformanceStatus.ON_TRACK);
        }
        // 지금 추세: 실적 ÷ 지난 일수(오늘 포함) × 그달 일수. 곱을 먼저 해 정수 나눗셈의 내림 오차를 줄인다
        long projected = recognized * month.lengthOfMonth() / today.getDayOfMonth();
        return new Progress(target, recognized, remaining,
                projected < target ? PerformanceStatus.AT_RISK : PerformanceStatus.ON_TRACK);
    }

    record Progress(Long target, long recognized, long remaining, PerformanceStatus status) {
    }

    /** 다음 달 영향 문구(임시). 목표가 없거나 실적 행이 없으면 null. */
    static PerformanceDetailResponse.NextMonthImpact nextMonthImpact(Progress progress,
            List<PerformanceDetailResponse.Tier> tiers) {
        if (progress.status() == PerformanceStatus.NO_TARGET
                || progress.status() == PerformanceStatus.INSUFFICIENT_DATA) {
            return null;
        }
        if (progress.status() == PerformanceStatus.ACHIEVED) {
            return new PerformanceDetailResponse.NextMonthImpact(
                    "다음 달에 " + label(progress.target()) + " 구간 혜택을 받아요");
        }
        return new PerformanceDetailResponse.NextMonthImpact(won(progress.remaining()) + " 더 쓰면 다음 달 "
                + label(progress.target()) + " 구간 혜택을 받아요");
    }

    /** "30만원 이상" · "1만 5,000원 이상"처럼 만원 단위로. null이면 null. */
    static String label(Long amount) {
        if (amount == null) {
            return null;
        }
        long man = amount / 10_000;
        long rest = amount % 10_000;
        NumberFormat format = NumberFormat.getIntegerInstance(Locale.KOREA);
        String text;
        if (man == 0) {
            text = format.format(rest) + "원";
        } else if (rest == 0) {
            text = format.format(man) + "만원";
        } else {
            text = format.format(man) + "만 " + format.format(rest) + "원";
        }
        return text + " 이상";
    }

    private static String won(long amount) {
        return NumberFormat.getIntegerInstance(Locale.KOREA).format(amount) + "원";
    }

    private static int daysLeft(YearMonth month, LocalDate today) {
        YearMonth current = YearMonth.from(today);
        if (month.isBefore(current)) {
            return 0;
        }
        if (month.isAfter(current)) {
            return month.lengthOfMonth();
        }
        return month.lengthOfMonth() - today.getDayOfMonth();
    }

    private Map<Long, CardProduct> productsOf(List<UserCard> owned) {
        Set<Long> ids = owned.stream().map(UserCard::getCardProductId).collect(Collectors.toSet());
        return products.findAllById(ids).stream().collect(Collectors.toMap(CardProduct::getId, Function.identity()));
    }

    /** 카드 목록과 같다: 별칭이 없으면 상품명. */
    private static String nickname(UserCard card, CardProduct product) {
        return card.getCardAlias() == null ? product.getProductName() : card.getCardAlias();
    }

    /** 범위 밖 월은 400 INVALID_REQUEST(fieldErrors yearMonth). */
    private static YearMonth checked(YearMonth yearMonth) {
        if (yearMonth.isBefore(MIN_MONTH) || yearMonth.isAfter(MAX_MONTH)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, Map.of("fieldErrors",
                    List.of(new ApiResponse.FieldErrorResponse("yearMonth", "yearMonth는 2000-01부터 2100-12 사이 월입니다."))));
        }
        return yearMonth;
    }
}
