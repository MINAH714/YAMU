package com.yamu.be.payment.history;

import com.yamu.be.card.domain.CardType;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.global.security.AuthUser;
import com.yamu.be.payment.PaymentPlan;
import com.yamu.be.payment.domain.PaymentStatus;
import com.yamu.be.payment.domain.TransactionSource;
import com.yamu.be.payment.history.PaymentHistoryReader.ExportRow;
import com.yamu.be.payment.history.PaymentHistoryService.Conditions;
import com.yamu.be.payment.history.PaymentHistoryService.Resolved;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 내역 CSV 내려받기(Task 5.3.6, API 명세 「결제 내역 CSV 내려받기」, FR-050·NFR-001). 결제 내역 목록과 같은 조건으로 전체를 낸다.
 *
 * <ul>
 *   <li>기간은 12개월까지다(400 RANGE_TOO_LARGE). from·to 중 한쪽만 주면 기간이 끝없이 길어지므로 같은 400이다. 아무것도 없으면 이번 달.</li>
 *   <li>사용자마다 분당 3번까지다(429 RATE_LIMITED, Redis 분 단위 카운터). Redis가 실패하면 막지 않고 남긴다(내려받기를 막을 만큼 위험하지 않다).
 *       행 수 초과로 400이 나는 요청도 센다. 초과 여부는 쿼리를 돌려야 알고, 그 쿼리가 서버 부담의 본체라서다.</li>
 *   <li>행 수는 {@value #MAX_ROWS}건까지다(임시). 넘으면 400 RANGE_TOO_LARGE로 기간을 줄이게 한다.</li>
 *   <li>엑셀에서 바로 열리게 UTF-8 BOM을 붙이고, 결제일시는 한국 시각 {@code yyyy-MM-dd HH:mm}이다(API의 UTC 규칙과 다름, 명세대로).
 *       시각은 목록의 기간·정렬과 같은 요청 시각이다. 승인 시각을 쓰면 자정 직전 요청·직후 승인 건이 10월 파일에 11-01로 나온다.</li>
 *   <li>카드번호는 끝 4자리만 보인다. 셀이 =·+·-·@로 시작하면 엑셀이 수식으로 읽지 않게 앞에 '를 붙인다(CSV 수식 주입 방지).</li>
 *   <li>청구예정액(임시): 승인 건만. 신용카드는 승인액 − 청구할인, 체크카드는 승인액(출금액). 취소·거절은 빈 칸.</li>
 * </ul>
 */
@Slf4j
@Service
public class PaymentExportService {

    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    static final int MAX_MONTHS = 12;
    static final int MAX_ROWS = 10_000;
    static final int PER_MINUTE = 3;
    static final String RATE_KEY_PREFIX = "payment:export:rate:";
    static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    static final String HEADER = "결제일시,가맹점,업종,카드,카드번호,결제방식,할부,금액,혜택,청구예정액,상태,실적인정,소비분류,메모";
    private static final DateTimeFormatter KOREAN_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final PaymentHistoryService history;
    private final PaymentHistoryReader reader;
    private final StringRedisTemplate redis;
    private final Clock clock;

    public PaymentExportService(PaymentHistoryService history, PaymentHistoryReader reader, StringRedisTemplate redis,
            Clock clock) {
        this.history = history;
        this.reader = reader;
        this.redis = redis;
        this.clock = clock;
    }

    /** CSV 파일. 이름은 기간을 담는다(한 달이면 {@code yamu-payments-2026-10.csv}). */
    public record CsvFile(String filename, byte[] content) {}

    @Transactional(readOnly = true)
    public CsvFile export(AuthUser me, YearMonth yearMonth, LocalDate from, LocalDate to, Long userCardId,
            PaymentStatus status, TransactionSource source, Conditions conditions) {
        Resolved resolved = history.resolve(me, yearMonth, from, to, userCardId, status, source, conditions);
        requireRange(resolved);
        checkRate(me.userId());
        List<ExportRow> rows = reader.findForExport(resolved.filter(), MAX_ROWS + 1);
        if (rows.size() > MAX_ROWS) {
            throw new BusinessException(ErrorCode.RANGE_TOO_LARGE, "내려받을 결제가 너무 많아요. 기간을 줄여 주세요.",
                    Map.of("maxRows", MAX_ROWS));
        }
        StringBuilder csv = new StringBuilder(HEADER).append("\r\n");
        for (ExportRow row : rows) {
            csv.append(line(row)).append("\r\n");
        }
        byte[] body = csv.toString().getBytes(StandardCharsets.UTF_8);
        byte[] content = new byte[BOM.length + body.length];
        System.arraycopy(BOM, 0, content, 0, BOM.length);
        System.arraycopy(body, 0, content, BOM.length, body.length);
        log.info("결제 내역 CSV 내려받기 userId={} rows={}", me.userId(), rows.size());
        return new CsvFile(filename(resolved), content);
    }

    /** 기간 끝이 시작 + 12개월 전날까지면 된다(예: 2026-01-01 ~ 2026-12-31). */
    private static void requireRange(Resolved resolved) {
        if (resolved.start() == null || resolved.endInclusive() == null) {
            throw new BusinessException(ErrorCode.RANGE_TOO_LARGE, "내려받을 기간의 시작일과 종료일을 모두 정해 주세요.",
                    Map.of("maxMonths", MAX_MONTHS));
        }
        if (!resolved.endInclusive().isBefore(resolved.start().plusMonths(MAX_MONTHS))) {
            throw new BusinessException(ErrorCode.RANGE_TOO_LARGE, Map.of("maxMonths", MAX_MONTHS));
        }
    }

    /** 한국 시각 분 단위 고정 창. 창마다 3번까지. 행 수 검사보다 먼저 세므로 1만 건 초과로 끝나는 요청도 센다(쿼리는 이미 돌았다). */
    private void checkRate(Long userId) {
        long minute = clock.instant().getEpochSecond() / 60;
        String key = RATE_KEY_PREFIX + userId + ":" + minute;
        Long count;
        try {
            count = redis.opsForValue().increment(key);
            if (count != null && count == 1) {
                redis.expire(key, Duration.ofMinutes(2));
            }
        } catch (RuntimeException e) {
            log.warn("CSV 내려받기 호출 제한을 확인하지 못해 그대로 진행 userId={}", userId, e);
            return;
        }
        if (count != null && count > PER_MINUTE) {
            long retryAfter = 60 - clock.instant().getEpochSecond() % 60;
            throw new BusinessException(ErrorCode.RATE_LIMITED, Map.of("retryAfterSeconds", retryAfter));
        }
    }

    private static String filename(Resolved resolved) {
        YearMonth startMonth = YearMonth.from(resolved.start());
        boolean wholeMonth = resolved.start().equals(startMonth.atDay(1))
                && resolved.endInclusive().equals(startMonth.atEndOfMonth());
        return wholeMonth ? "yamu-payments-" + startMonth + ".csv"
                : "yamu-payments-" + resolved.start() + "_" + resolved.endInclusive() + ".csv";
    }

    static String line(ExportRow row) {
        PaymentListResponse.Item item = row.item();
        // 결제일시는 목록의 기간·정렬과 같은 요청 시각. 승인 시각은 자정을 넘길 수 있어 전달 파일에 다음 달 날짜가 섞인다
        LocalDateTime at = item.requestedAt();
        PaymentListResponse.Card card = item.card();
        return String.join(",",
                cell(at.atOffset(ZoneOffset.UTC).atZoneSameInstant(KST).format(KOREAN_TIME)),
                cell(item.merchant().name()),
                cell(item.merchant().categoryName() != null ? item.merchant().categoryName()
                        : item.merchant().categoryCode()),
                cell(card == null ? null : card.nickname()),
                cell(row.cardLast4() == null ? null : "****-****-****-" + row.cardLast4()),
                cell(item.source() == TransactionSource.SEED ? "등록 전 거래" : "야무 통합 바코드"),
                cell(item.plan() == PaymentPlan.INSTALLMENT ? item.installmentMonths() + "개월" : "일시불"),
                cell(Long.toString(item.amount())),
                cell(Long.toString(item.benefitAmount())),
                cell(expectedCharge(row)),
                cell(statusName(item.status())),
                cell(item.performanceRecognized() ? "인정" : "미인정"),
                cell(spendingClassName(item.spendingClass())),
                cell(item.memo()));
    }

    /** 청구예정액(임시): 승인 건의 신용카드는 승인액 − 청구할인, 체크카드는 승인액. 그 밖은 빈 칸. */
    private static String expectedCharge(ExportRow row) {
        PaymentListResponse.Item item = row.item();
        if (item.status() != PaymentStatus.APPROVED || row.approvedAmount() == null) {
            return null;
        }
        boolean credit = item.card() != null && item.card().cardType() == CardType.CREDIT;
        long amount = credit ? Math.max(row.approvedAmount() - row.billingDiscount(), 0) : row.approvedAmount();
        return Long.toString(amount);
    }

    private static String statusName(PaymentStatus status) {
        return switch (status) {
            case APPROVED -> "승인";
            case CANCELED -> "취소";
            case DECLINED -> "거절";
            default -> status.name();
        };
    }

    private static String spendingClassName(String spendingClass) {
        if (spendingClass == null) {
            return null;
        }
        return switch (spendingClass) {
            case "LIVING" -> "생활 소비";
            case "LARGE" -> "큰 결제";
            case "EXCLUDED" -> "실적 제외";
            default -> spendingClass;
        };
    }

    /**
     * CSV 셀(RFC 4180). 쉼표·따옴표·줄바꿈이 있으면 따옴표로 감싸고 따옴표는 두 번 쓴다.
     * =·+·-·@·탭·CR로 시작하면 앞에 '를 붙여 엑셀이 수식으로 실행하지 않게 한다. null은 빈 칸.
     */
    static String cell(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String safe = "=+-@\t\r".indexOf(value.charAt(0)) >= 0 ? "'" + value : value;
        if (safe.contains(",") || safe.contains("\"") || safe.contains("\n") || safe.contains("\r")) {
            return "\"" + safe.replace("\"", "\"\"") + "\"";
        }
        return safe;
    }
}
