package com.yamu.be.benefitlimit;

import com.yamu.be.card.CardIssuers;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 혜택 한도 현황(API 명세 V2 #32). 응답 모양은 앱 {@code PerformanceModels.kt}의 {@code BenefitLimitsDto}에 맞춘다
 * (S15P31E103-374).
 *
 * @param yearMonth    한국 기준 이번 달(YYYY-MM)
 * @param summary      원화 월 한도의 합계
 * @param cards        등록된 카드(ACTIVE·INACTIVE). 순서는 카드 목록과 같다
 * @param dataSyncedAt 외부 동기화 시각. 한도 사용량은 결제 승인·취소 때 바로 바뀌어 동기화 개념이 없으므로 null
 */
public record BenefitLimitsResponse(String yearMonth, Summary summary, List<Card> cards, LocalDateTime dataSyncedAt) {

    /**
     * 원화(KRW) 월 한도만 더한다. 횟수·포인트 한도는 원으로 더할 수 없어 뺀다.
     *
     * @param receivedAmount  목록에 있는 원화 월 한도의 이번 달 사용량 합(received + remaining = 이번 달 원화 월 한도 합)
     * @param remainingAmount 목록에 있는 원화 월 한도의 남은 한도 합. 잠긴 한도는 0
     */
    public record Summary(long receivedAmount, long remainingAmount) {
    }

    /**
     * @param nickname        카드 별칭. 없으면 상품명(카드 목록과 같음)
     * @param remainingAmount 이 카드 원화 월 한도의 남은 한도 합. 원화 월 한도가 없으면 null
     * @param limits          월 한도 그룹당 한 줄. 여러 혜택이 같이 쓰는 공유 한도도 한 줄이다. 건별 한도·한도 없는 혜택은 없다.
     *                        비어 있으면 앱은 "한도 없음"으로 보인다
     */
    public record Card(long userCardId, String nickname, CardIssuers.Issuer issuer, Long remainingAmount,
            List<Limit> limits) {
    }

    /**
     * @param name            한도 그룹 이름(limit_group.group_name)
     * @param remainingAmount 남은 한도(원). 원화 한도가 아니면(횟수·포인트) null
     * @param status          {@link LimitStatus}
     * @param lockedReason    LOCKED일 때 사유. 예: "지난달 실적이 30만원에 못 미쳐요". 그 밖에는 null
     */
    public record Limit(String name, Long remainingAmount, LimitStatus status, String lockedReason) {
    }
}
