package com.yamu.be.payment.performance;

import com.yamu.be.card.CardActivityReader;
import com.yamu.be.card.CardBenefitSummaryReader;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 결제 승인·상세의 "기준 ○원 · ○원 남았어요" 줄(앱 칸 {@code performance.recognizedTotalAfter}·{@code targetAmount},
 * S15P31E103-390). 카드 목록 API({@code GET /api/cards}의 {@code performance})와 같은 숫자가 나오도록 같은 리더를 쓴다.
 *
 * <ul>
 *   <li>누적: 그 카드·그 실적 월의 월 실적 합계({@code card_monthly_performance}). 행이 없으면 0이다(S15P31E103-324와 같은 규칙).
 *       지금 값이라 그 뒤의 결제·취소가 반영된다(2026-11-16 결정: 상세는 결제 당시가 아니라 지금 값).</li>
 *   <li>목표: BE1 -308 임시 규칙({@link CardBenefitSummaryReader.Summary#targetFor}). 누적보다 큰 실적 기준 중 가장 작은 값,
 *       다 넘었으면 가장 큰 기준, 실적 조건이 없는 카드는 null이다. 혜택 유효 기간은 오늘(한국 날짜)로 본다(카드 목록과 같음).</li>
 * </ul>
 *
 * 실적 인정액 미확인·취소처럼 이 줄을 보이지 않을 결제는 부르는 쪽이 거른다.
 */
@Component
public class PaymentPerformanceTotals {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final CardActivityReader activity;
    private final CardBenefitSummaryReader summaries;
    private final Clock clock;

    public PaymentPerformanceTotals(CardActivityReader activity, CardBenefitSummaryReader summaries, Clock clock) {
        this.activity = activity;
        this.summaries = summaries;
        this.clock = clock;
    }

    /**
     * @param recognizedTotalAfter 그 카드·그 실적 월의 지금 누적 실적(원)
     * @param targetAmount         목표 실적(원). 실적 조건이 없는 카드는 null
     */
    public record Total(long recognizedTotalAfter, Long targetAmount) {}

    /**
     * @param performanceMonth 결제의 실적 월(1일). 아무 날이어도 그 달로 본다
     */
    public Total of(long userCardId, long cardProductId, LocalDate performanceMonth) {
        YearMonth month = YearMonth.from(performanceMonth);
        long total = activity.recognizedAmounts(List.of(userCardId), month).getOrDefault(userCardId, 0L);
        CardBenefitSummaryReader.Summary summary = summaries.summaries(List.of(cardProductId),
                LocalDate.ofInstant(clock.instant(), KST)).get(cardProductId);
        return new Total(total, summary == null ? null : summary.targetFor(total));
    }
}
