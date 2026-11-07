package com.yamu.be.payment.billing.query;

import com.yamu.be.account.BankNames;
import com.yamu.be.global.common.ApiResponse.FieldErrorResponse;
import com.yamu.be.global.common.PageResponse;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.payment.billing.RefundStatus;
import com.yamu.be.payment.billing.query.RefundQueryReader.Filter;
import com.yamu.be.payment.billing.query.RefundQueryReader.Pending;
import com.yamu.be.payment.billing.query.RefundQueryReader.Row;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 환급 내역 조회(노션 API 명세 V2 「환급 내역 조회」, Task 5.4.8, FR-068·POL-006). 읽기만 한다.
 *
 * <p>기간은 생성일(한국 날짜, 포함)이고 기본은 오늘을 포함한 최근 3개월, 최대 12개월이다. 남의 환급은 보이지 않는다.
 * 체크카드 취소 환불과 무효 환급의 표시 방식은 {@link RefundListResponse}를 본다.
 */
@Service
@Transactional(readOnly = true)
public class RefundQueryService {

    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    static final int DEFAULT_MONTHS = 3;
    /** 조회 기간 상한. 결제 내역 CSV(S15P31E103-302)와 같은 12개월이다(임시, 명세에 없음) */
    static final int MAX_MONTHS = 12;

    private final RefundQueryReader reader;
    private final Clock clock;

    RefundQueryService(RefundQueryReader reader, Clock clock) {
        this.reader = reader;
        this.clock = clock;
    }

    public RefundListResponse list(long userId, RefundStatus status, LocalDate from, LocalDate to, int page, int size) {
        LocalDate today = LocalDate.ofInstant(clock.instant(), KST);
        if (from != null && to != null && from.isAfter(to)) {
            throw invalid("from", "from은 to보다 늦을 수 없습니다.");
        }
        if (from != null && to == null && from.isAfter(today)) {
            throw invalid("from", "from은 오늘보다 늦을 수 없습니다.");
        }
        LocalDate end = to != null ? to : today;
        LocalDate start = from != null ? from : end.minusMonths(DEFAULT_MONTHS).plusDays(1);
        // 302 CSV와 같은 규칙: 종료일이 시작일 + 12개월보다 앞이어야 한다(2025-11-09 ~ 2026-11-08은 되고 2025-11-08 ~ 2026-11-08은 안 됨)
        if (!end.isBefore(start.plusMonths(MAX_MONTHS))) {
            throw invalid("from", "조회 기간은 " + MAX_MONTHS + "개월 이하여야 합니다.");
        }
        Filter filter = new Filter(userId, status, startOfKstDay(start), startOfKstDay(end.plusDays(1)));
        long total = reader.count(filter);
        List<RefundListResponse.Item> items = total == 0 ? List.of()
                : reader.page(filter, PageResponse.offset(page, size), size).stream().map(RefundQueryService::item)
                        .toList();
        Pending pending = reader.pending(userId);
        return new RefundListResponse(new RefundListResponse.Summary(pending.amount(), pending.count(),
                pending.estimatedAmount(), pending.estimatedCount()), PageResponse.of(items, page, size, total));
    }

    private static RefundListResponse.Item item(Row row) {
        boolean check = RefundQueryReader.CHECK_CANCEL.equals(row.reason());
        // 체크카드 환불은 취소와 함께 입금이 끝나므로 기한은 취소일(한국 날짜)이다
        LocalDate due = check && row.completedAt() != null ? koreanDate(row.completedAt()) : row.dueDate();
        // 출금 전 청구서의 환급은 기한이 아직 없고 금액은 예상값이다(RefundStatus.PENDING 주석)
        boolean estimated = !check && row.status() == RefundStatus.PENDING && row.dueDate() == null;
        RefundListResponse.Account account = row.accountId() == null ? null
                : new RefundListResponse.Account(row.accountId(), BankNames.of(row.bankCode()),
                        "****-****-****-" + (row.accountLast4() == null ? "****" : row.accountLast4()));
        return new RefundListResponse.Item(row.refundId(), row.paymentId(), row.billItemId(), row.billId(),
                row.userCardId(), row.cardAlias() != null ? row.cardAlias() : row.productName(), row.merchantName(),
                row.reason(), row.amount(), estimated, row.status(), row.createdAt(), row.scheduledAfter(), due,
                row.completedAt(), row.finTransactionNo(), account);
    }

    private static BusinessException invalid(String field, String reason) {
        return new BusinessException(ErrorCode.INVALID_REQUEST,
                Map.of("fieldErrors", List.of(new FieldErrorResponse(field, reason))));
    }

    private static LocalDateTime startOfKstDay(LocalDate date) {
        return date.atStartOfDay(KST).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    private static LocalDate koreanDate(LocalDateTime utc) {
        return utc.atOffset(ZoneOffset.UTC).atZoneSameInstant(KST).toLocalDate();
    }
}
