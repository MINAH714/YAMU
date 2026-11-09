package com.yamu.be.report;

import com.yamu.be.merchant.MerchantCategories;
import com.yamu.be.report.ReportReader.PaymentRow;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.ToLongFunction;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 월간 소비·혜택 리포트(S15P31E103-372). 월은 한국 기준이고, 결제 내역 목록과 같이 POS 요청 시각으로 자른다.
 * 금액은 승인 결제만 더한다(취소·거절 제외). 결정 사항(2026-11-09)은 응답 레코드 Javadoc에 있다.
 */
@Service
class ReportService {

    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    static final String USER_SELECTED = "USER_SELECTED";
    static final String LIMIT_PARTIAL = "LIMIT_PARTIAL";

    private final ReportReader reader;
    private final Clock clock;

    ReportService(ReportReader reader, Clock clock) {
        this.reader = reader;
        this.clock = clock;
    }

    /** @param yearMonth 한국 기준 월. null이면 이번 달 */
    @Transactional(readOnly = true)
    public MonthlyReportResponse monthly(long userId, YearMonth yearMonth) {
        LocalDate today = LocalDate.now(clock.withZone(KST));
        YearMonth month = yearMonth != null ? yearMonth : YearMonth.from(today);
        boolean partial = !month.isBefore(YearMonth.from(today));
        LocalDate periodTo = month.equals(YearMonth.from(today)) ? today : month.atEndOfMonth();

        List<PaymentRow> rows = reader.approvedPayments(userId, start(month.atDay(1)), start(month.plusMonths(1).atDay(1)));
        long total = sum(rows, PaymentRow::amount);
        long living = rows.stream().filter(r -> "LIVING".equals(r.spendingClass())).mapToLong(PaymentRow::amount).sum();

        YearMonth prev = month.minusMonths(1);
        LocalDate prevEnd = prev.atDay(Math.min(periodTo.getDayOfMonth(), prev.lengthOfMonth()));
        long prevSamePeriodLiving = reader.spent(userId, start(prev.atDay(1)), start(prevEnd.plusDays(1))).living();
        long prevMonthTotal = reader.spent(userId, start(prev.atDay(1)), start(month.atDay(1))).total();

        return new MonthlyReportResponse(month.toString(), partial, periodTo, total, living, prevSamePeriodLiving,
                prevMonthTotal, byCategory(rows, PaymentRow::amount),
                new MonthlyReportResponse.BenefitSummary(sum(rows, ReportService::benefit),
                        sum(rows, PaymentRow::confirmedAmount)));
    }

    /** @param yearMonth 한국 기준 월. null이면 지난달(명세 기본값: 확정이 끝난 달) */
    @Transactional(readOnly = true)
    public BenefitReportResponse benefits(long userId, YearMonth yearMonth) {
        YearMonth current = YearMonth.now(clock.withZone(KST));
        YearMonth month = yearMonth != null ? yearMonth : current.minusMonths(1);
        List<PaymentRow> rows = reader.approvedPayments(userId, start(month.atDay(1)), start(month.plusMonths(1).atDay(1)));

        boolean allConfirmed = rows.stream().allMatch(r -> r.confirmedLines() >= r.benefitLines());
        String status = month.isBefore(current) && allConfirmed ? "CONFIRMED" : "PARTIAL";

        List<BenefitReportResponse.Difference> differences = new ArrayList<>();
        long yamuPickExtra = 0;
        for (PaymentRow row : rows) {
            missed(row).ifPresent(differences::add);
            yamuPickExtra += pickExtra(row);
        }
        long missedTotal = differences.stream().mapToLong(d -> d.expectedAmount() - d.actualAmount()).sum();
        List<MonthlyReportResponse.CategoryAmount> categories = byCategory(rows, ReportService::benefit).stream()
                .filter(c -> c.amount() > 0).toList();
        return new BenefitReportResponse(month.toString(), status,
                new BenefitReportResponse.Summary(sum(rows, ReportService::benefit), sum(rows, PaymentRow::confirmedAmount),
                        yamuPickExtra, missedTotal),
                differences, categories);
    }

    /**
     * 추천 1순위 대비 놓친 혜택. 판정하지 않는 결제: 추천 결과가 없는 결제(SEED·옛 결제), 원화로 환산되지 않은 혜택 줄이 있는 결제
     * (실제 받은 금액을 모르면 임의로 정하지 않는다, NFR-006).
     */
    static Optional<BenefitReportResponse.Difference> missed(PaymentRow row) {
        if (!"YAMU".equals(row.source()) || row.bestCardId() == null || row.bestNetBenefit() == null
                || row.unconvertedLines() > 0) {
            return Optional.empty();
        }
        long best = floor(row.bestNetBenefit());
        long actual = benefit(row);
        if (best <= actual) {
            return Optional.empty();
        }
        String reason = row.bestCardId().equals(row.userCardId()) ? LIMIT_PARTIAL : USER_SELECTED;
        return Optional.of(new BenefitReportResponse.Difference(row.paymentId(), row.approvedAt(),
                row.merchantName(), row.userCardId(), best, actual, reason));
    }

    /** 1순위 카드로 승인했을 때 1순위가 2순위보다 더 준 순혜택(원 단위 내림). 2순위가 없으면 0. */
    static long pickExtra(PaymentRow row) {
        if (row.bestCardId() == null || !row.bestCardId().equals(row.userCardId()) || row.bestNetBenefit() == null
                || row.secondNetBenefit() == null) {
            return 0;
        }
        return Math.max(0, floor(row.bestNetBenefit().subtract(row.secondNetBenefit())));
    }

    private static List<MonthlyReportResponse.CategoryAmount> byCategory(List<PaymentRow> rows,
            ToLongFunction<PaymentRow> amount) {
        Map<String, Long> totals = rows.stream().collect(Collectors.groupingBy(PaymentRow::categoryCode,
                LinkedHashMap::new, Collectors.summingLong(amount)));
        long total = totals.values().stream().mapToLong(Long::longValue).sum();
        Map<String, String> names = MerchantCategories.list().stream().collect(Collectors.toMap(
                MerchantCategories.Category::merchantCategoryCode, MerchantCategories.Category::merchantCategoryName));
        return totals.entrySet().stream()
                .map(e -> new MonthlyReportResponse.CategoryAmount(e.getKey(), names.getOrDefault(e.getKey(), e.getKey()),
                        e.getValue(), ratio(e.getValue(), total)))
                .sorted(Comparator.comparingLong(MonthlyReportResponse.CategoryAmount::amount).reversed()
                        .thenComparing(MonthlyReportResponse.CategoryAmount::categoryCode))
                .toList();
    }

    private static BigDecimal ratio(long amount, long total) {
        if (total == 0) {
            return BigDecimal.ZERO.setScale(1);
        }
        return BigDecimal.valueOf(amount).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(total), 1, RoundingMode.HALF_UP);
    }

    private static long benefit(PaymentRow row) {
        return row.benefitAmount() == null ? 0 : row.benefitAmount();
    }

    private static long sum(List<PaymentRow> rows, ToLongFunction<PaymentRow> amount) {
        return rows.stream().mapToLong(amount).sum();
    }

    private static long floor(BigDecimal value) {
        return value.setScale(0, RoundingMode.FLOOR).longValue();
    }

    /** 한국 날짜 0시를 UTC 시각으로. */
    private static LocalDateTime start(LocalDate kstDate) {
        return kstDate.atStartOfDay(KST).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }
}
