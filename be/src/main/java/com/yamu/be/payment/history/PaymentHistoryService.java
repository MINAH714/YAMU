package com.yamu.be.payment.history;

import com.yamu.be.global.common.ApiResponse.FieldErrorResponse;
import com.yamu.be.global.common.PageResponse;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.global.security.AuthUser;
import com.yamu.be.payment.PaymentPlan;
import com.yamu.be.payment.approve.ApprovalNumbers;
import com.yamu.be.payment.approve.FailureCode;
import com.yamu.be.payment.domain.PaymentStatus;
import com.yamu.be.payment.domain.TransactionSource;
import com.yamu.be.payment.history.PaymentHistoryReader.Filter;
import com.yamu.be.payment.history.PaymentHistoryReader.PaymentRow;
import com.yamu.be.payment.history.PaymentHistoryReader.Totals;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사용자 결제 내역 목록·상세. 기간은 한국 날짜로 받아 POS 요청 시각(UTC)의 범위로 바꾼다.
 *
 * <p>명세는 "승인일 범위"이지만 거절 건은 승인 시각이 없어 모든 상태를 요청 시각으로 거른다(임시, 승인과 요청은 확정 시간 60초 안쪽 차이).
 * 남의 결제는 목록에서 보이지 않고 상세는 없는 결제와 같은 404다(FR-001). 남의 카드로 카드 필터를 걸어도 404가 아니라 빈 목록이라
 * 카드가 있는지 드러나지 않는다.
 */
@Service
class PaymentHistoryService {

    static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** 목록에서 고를 수 있는 상태. 처리 중·요청·만료는 결제 내역이 아니다(명세 「결제 내역 목록 조회」). */
    static final Set<PaymentStatus> LISTED = EnumSet.of(PaymentStatus.APPROVED, PaymentStatus.DECLINED,
            PaymentStatus.CANCELED);
    static final List<PaymentStatus> DEFAULT_STATUSES = List.of(PaymentStatus.APPROVED, PaymentStatus.CANCELED);

    private final PaymentHistoryReader reader;
    private final Clock clock;

    PaymentHistoryService(PaymentHistoryReader reader, Clock clock) {
        this.reader = reader;
        this.clock = clock;
    }

    /**
     * 목록 한 페이지와 필터 전체 합계. 두 쿼리를 한 읽기 트랜잭션에서 읽어, 그사이 승인·취소가 끼어도 합계와 목록이 같은 시점이다.
     *
     * @param yearMonth 한국 기준 월. {@code from}·{@code to}와 함께 쓰지 않고, 셋 다 없으면 이번 달이다
     * @param from      한국 날짜(포함)
     * @param to        한국 날짜(포함)
     * @param status    null이면 승인+취소
     */
    @Transactional(readOnly = true)
    public PaymentListResponse list(AuthUser me, YearMonth yearMonth, LocalDate from, LocalDate to, Long userCardId,
            PaymentStatus status, TransactionSource source, int page, int size) {
        if (yearMonth != null && (from != null || to != null)) {
            throw invalid("yearMonth", "yearMonth는 from·to와 함께 쓸 수 없습니다.");
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw invalid("from", "from은 to보다 늦을 수 없습니다.");
        }
        if (status != null && !LISTED.contains(status)) {
            throw invalid("status", "status는 APPROVED·DECLINED·CANCELED 중 하나입니다.");
        }
        LocalDate start = from;
        LocalDate endInclusive = to;
        if (from == null && to == null) {
            YearMonth month = yearMonth != null ? yearMonth : YearMonth.now(clock.withZone(KST));
            start = month.atDay(1);
            endInclusive = month.atEndOfMonth();
        }
        Filter filter = new Filter(me.userId(), status != null ? List.of(status) : DEFAULT_STATUSES,
                startOfKstDay(start), endInclusive == null ? null : startOfKstDay(endInclusive.plusDays(1)),
                userCardId, source);

        Totals totals = reader.totals(filter);
        List<PaymentListResponse.Item> items = totals.count() == 0 ? List.of()
                : reader.findPage(filter, PageResponse.offset(page, size), size);
        PaymentListResponse.Summary summary = new PaymentListResponse.Summary(totals.count(), totals.amount(),
                totals.benefit(), null);
        return new PaymentListResponse(summary, PageResponse.of(items, page, size, totals.count()));
    }

    /** 결제 한 건의 상세. 없거나 남의 결제면 404 RESOURCE_NOT_FOUND. */
    @Transactional(readOnly = true)
    public PaymentDetailResponse detail(AuthUser me, Long paymentId) {
        PaymentRow row = reader.findOne(paymentId, me.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        PaymentListResponse.Item item = row.item();
        List<PaymentDetailResponse.BillItem> billItems = reader.findBillItems(paymentId);
        return new PaymentDetailResponse(
                item.paymentId(),
                item.source(),
                item.status(),
                row.channel(),
                item.merchant(),
                item.amount(),
                item.plan(),
                item.installmentMonths(),
                row.approvedAmount(),
                selectedCard(item, row),
                item.approvedAt() == null ? null : ApprovalNumbers.of(item.approvedAt(), item.paymentId()),
                item.approvedAt(),
                row.canceledAt(),
                reader.findBenefits(paymentId),
                performance(item, row),
                reader.findLimitImpacts(paymentId),
                billItems,
                reader.findRefunds(paymentId),
                null,
                statusHistory(item, row),
                failure(row.failureCode()),
                installmentPolicy(item, billItems));
    }

    private static PaymentDetailResponse.SelectedCard selectedCard(PaymentListResponse.Item item, PaymentRow row) {
        if (item.card() == null) {
            return null;
        }
        String masked = row.cardLast4() == null ? null : "****-****-****-" + row.cardLast4();
        return new PaymentDetailResponse.SelectedCard(item.card().userCardId(), item.card().nickname(), masked,
                item.card().cardType(), null, null);
    }

    private static PaymentDetailResponse.Performance performance(PaymentListResponse.Item item, PaymentRow row) {
        if (row.performanceMonth() == null || row.recognizedPerformanceAmount() == null) {
            return null;
        }
        return new PaymentDetailResponse.Performance(YearMonth.from(row.performanceMonth()).toString(),
                row.recognizedPerformanceAmount(), item.performanceRecognized(), null);
    }

    /** 저장된 시각(요청·승인·취소)으로 만든다. 처리 중·거절·만료는 시각을 저장하지 않아 현재 상태일 때만 시각 없이 붙인다. */
    static List<PaymentDetailResponse.StatusChange> statusHistory(PaymentListResponse.Item item, PaymentRow row) {
        List<PaymentDetailResponse.StatusChange> history = new ArrayList<>();
        history.add(new PaymentDetailResponse.StatusChange(PaymentStatus.REQUESTED, item.requestedAt()));
        if (item.approvedAt() != null) {
            history.add(new PaymentDetailResponse.StatusChange(PaymentStatus.APPROVED, item.approvedAt()));
        }
        if (row.canceledAt() != null) {
            history.add(new PaymentDetailResponse.StatusChange(PaymentStatus.CANCELED, row.canceledAt()));
        }
        PaymentStatus last = history.get(history.size() - 1).status();
        if (item.status() != last) {
            history.add(new PaymentDetailResponse.StatusChange(item.status(), null));
        }
        return history;
    }

    private static PaymentDetailResponse.Failure failure(String code) {
        if (code == null) {
            return null;
        }
        String message = Arrays.stream(FailureCode.values()).filter(f -> f.name().equals(code))
                .map(FailureCode::message).findFirst().orElse(null);
        return new PaymentDetailResponse.Failure(code, message);
    }

    /** 무이자를 적용했다는 근거(회차가 모두 수수료 0원)가 있을 때만 표시한다. 근거가 없으면 무이자로 보지 않는다. */
    static String installmentPolicy(PaymentListResponse.Item item, List<PaymentDetailResponse.BillItem> billItems) {
        boolean interestFree = item.plan() == PaymentPlan.INSTALLMENT && item.source() == TransactionSource.YAMU
                && !billItems.isEmpty()
                && billItems.stream().allMatch(b -> b.feeAmount() != null && b.feeAmount() == 0);
        return interestFree ? PaymentDetailResponse.YAMU_INTEREST_FREE : null;
    }

    private static LocalDateTime startOfKstDay(LocalDate date) {
        return date == null ? null : date.atStartOfDay(KST).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    private static BusinessException invalid(String field, String reason) {
        return new BusinessException(ErrorCode.INVALID_REQUEST,
                Map.of("fieldErrors", List.of(new FieldErrorResponse(field, reason))));
    }
}
