package com.yamu.be.performance;

import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 유불리 분석의 자연 소비 원본: 최근 완료 3개월의 "같은 기간"(오늘 다음 날~그달 말일) 인정 실적(S15P31E103-375).
 *
 * <ul>
 *   <li>그달에 {@code card_monthly_performance} 행이 있는 달만 센다. 행이 없는 달(카드가 없던 달)은 0원이 아니라 모르는 달이다.</li>
 *   <li>결제는 승인(APPROVED) 상태이고 인정액이 0보다 큰 것만 더한다. 인정액 미확인(NULL)은 빼고, 취소된 결제는 상태가 바뀌어 빠진다.</li>
 *   <li>날짜는 승인 시각(UTC 저장)을 한국 날짜로 바꿔 고른다. 실적 월은 결제 행의 {@code performance_month}로 고른다.</li>
 * </ul>
 *
 * <p>모든 쿼리에 {@code user_id}를 넣어 남의 결제를 읽지 않는다.
 */
@Component
class PerformanceHistoryReader {

    static final int HISTORY_MONTHS = 3;
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final NamedParameterJdbcTemplate jdbc;

    PerformanceHistoryReader(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param today 한국 날짜 오늘. 각 달에서 오늘 날짜 다음 날부터 말일까지를 센다
     * @return 실적 행이 있는 달마다 같은 기간 인정 실적 합. 오래된 달부터
     */
    List<PerformanceAnalyzer.MonthSample> samePeriod(long userId, long userCardId, LocalDate today) {
        YearMonth current = YearMonth.from(today);
        List<Date> months = new ArrayList<>();
        for (int i = HISTORY_MONTHS; i >= 1; i--) {
            months.add(Date.valueOf(current.minusMonths(i).atDay(1)));
        }
        MapSqlParameterSource params = new MapSqlParameterSource("userId", userId)
                .addValue("cardId", userCardId).addValue("months", months);

        Set<YearMonth> known = new HashSet<>();
        jdbc.query("""
                select m.performance_month from card_monthly_performance m
                join user_card c on c.user_card_id = m.user_card_id
                where c.user_id = :userId and m.user_card_id = :cardId and m.performance_month in (:months)
                """, params, rs -> {
                    known.add(YearMonth.from(rs.getDate(1).toLocalDate()));
                });

        Map<YearMonth, Long> sums = new LinkedHashMap<>();
        for (Date month : months) {
            YearMonth ym = YearMonth.from(month.toLocalDate());
            if (known.contains(ym)) {
                sums.put(ym, 0L);
            }
        }
        if (sums.isEmpty()) {
            return List.of();
        }
        int fromDay = today.getDayOfMonth() + 1;
        jdbc.query("""
                select p.performance_month, p.approved_at, p.recognized_performance_amount
                from payment_transaction p
                where p.user_id = :userId and p.user_card_id = :cardId and p.status = 'APPROVED'
                  and p.performance_month in (:months) and p.recognized_performance_amount > 0
                """, params, rs -> {
                    YearMonth month = YearMonth.from(rs.getDate(1).toLocalDate());
                    LocalDateTime approvedUtc = rs.getObject(2, LocalDateTime.class);
                    if (!sums.containsKey(month) || approvedUtc == null) {
                        return;
                    }
                    LocalDate approvedKst = approvedUtc.atOffset(ZoneOffset.UTC).atZoneSameInstant(KST).toLocalDate();
                    if (approvedKst.getDayOfMonth() >= fromDay) {
                        sums.merge(month, rs.getLong(3), Long::sum);
                    }
                });
        return sums.entrySet().stream()
                .map(e -> new PerformanceAnalyzer.MonthSample(e.getKey(), e.getValue()))
                .toList();
    }
}
