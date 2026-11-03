package com.yamu.be.payment.approve;

import com.yamu.be.payment.billing.InstallmentPlan;
import com.yamu.be.payment.recommend.RecommendationResult.BenefitLine;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 승인 실행 결과. 확정 거절은 정상 결과(200 + DECLINED)이고, 결과 불명(금융망 무응답)은 결과가 아니라 예외다.
 */
public sealed interface ApprovalOutcome permits ApprovalOutcome.Approved, ApprovalOutcome.Declined {

    /**
     * 승인.
     *
     * @param approvedAmount              확정 승인액(원). 전액 즉시할인이면 0
     * @param chargedAmount               체크카드는 즉시할인을 뺀 출금액, 신용카드는 승인 원금(청구할인은 청구 때)
     * @param recognizedPerformanceAmount 승인 시점에 확정한 실적 인정액. 실적 제외 거래면 0
     * @param performanceMonth            실적을 합산할 월(한국 날짜, 1일)
     * @param benefits                    적용한 혜택. 없으면 빈 목록
     * @param finRequestNo                체크카드 출금에 쓴 기관거래고유번호. 결과를 저장할 때 행의 번호와 같아야 한다(늦게 온 옛 시도의 결과 차단).
     *                                    신용카드·0원 승인은 null
     * @param finTransactionNo            체크카드 출금의 금융망 거래 번호. 신용카드·0원 승인은 null
     * @param installments                신용카드의 청구 계획(회차별 원금·청구 월·출금 예정일). 결과 저장이 회차로 적는다. 체크카드는 null
     */
    record Approved(long approvedAmount, long chargedAmount, long recognizedPerformanceAmount,
            LocalDate performanceMonth, List<BenefitLine> benefits, String finRequestNo, String finTransactionNo,
            InstallmentPlan installments)
            implements ApprovalOutcome {
        public Approved {
            Objects.requireNonNull(performanceMonth, "performanceMonth");
            benefits = benefits == null ? List.of() : List.copyOf(benefits);
        }

        public boolean hasInstallments() {
            return installments != null;
        }
    }

    /**
     * 확정 거절. 돈이 빠지지 않았고 집계하지 않는다(FR-020). 마감 전이면 다른 카드로 재승인할 수 있다.
     *
     * @param message 앱에 보일 문구. null이면 사유의 기본 문구
     * @param details 명세의 {@code failure.details}(예: availableBalance). 없으면 빈 맵
     */
    record Declined(FailureCode code, String message, Map<String, Object> details) implements ApprovalOutcome {
        public Declined {
            Objects.requireNonNull(code, "code");
            message = message == null ? code.message() : message;
            details = details == null ? Map.of() : Map.copyOf(details);
        }

        public static Declined of(FailureCode code) {
            return new Declined(code, null, null);
        }
    }
}
