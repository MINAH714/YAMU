package com.yamu.be.payment.approve;

import com.yamu.be.payment.recommend.RecommendationResult.PerformanceRecognition;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 승인이 저장할 실적 인정액·실적 월(Task 5.2.11). 값은 BE3 실적 인정 계산 결과를 그대로 쓰고, 결제는 다시 계산하지 않는다.
 *
 * <ul>
 *   <li>인정액: 0은 약관상 실적 제외 확정, 양수는 인정, null은 미확인이다. 미확인은 월 실적 합계에 더하지 않는다.</li>
 *   <li>실적 월: BE3가 정한 월. BE3가 정하지 않았으면(인정월 규칙이 없는 상품·최소 실적 0 상품) 명세 기본값인 승인월(한국 날짜)이다.</li>
 * </ul>
 */
final class ApprovalPerformance {

    private ApprovalPerformance() {
    }

    /**
     * @param recognition 승인할 카드의 인정 값. 계산되지 않았으면 null
     * @param withBenefits 추천 후보의 혜택을 적용한 승인인지. 아니면(추천 밖 카드·재계산 뒤 후보 아님) 혜택 없이 원금으로 승인한다
     * @return 저장할 인정액. 혜택 없이 승인했는데 BE3가 0을 냈으면 미확인(null)이다(임시): BE3의 0에는 "할인받은 거래 실적 제외"처럼
     *         혜택 계산을 전제로 한 0이 섞여 있어, 혜택을 받지 않은 이 승인에는 그 0이 맞는지 결제가 가릴 수 없다
     */
    static Long amount(PerformanceRecognition recognition, boolean withBenefits) {
        if (recognition == null) {
            return null;
        }
        Long amount = recognition.amount();
        if (!withBenefits && amount != null && amount == 0) {
            return null;
        }
        return amount;
    }

    static LocalDate month(PerformanceRecognition recognition, LocalDateTime approvedAtUtc) {
        if (recognition != null && recognition.month() != null) {
            return recognition.month().atDay(1);
        }
        return ApprovalDates.performanceMonth(approvedAtUtc);
    }
}
