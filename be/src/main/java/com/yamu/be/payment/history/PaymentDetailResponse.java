package com.yamu.be.payment.history;

import com.yamu.be.card.CardIssuers;
import com.yamu.be.card.domain.CardType;
import com.yamu.be.payment.PaymentPlan;
import com.yamu.be.payment.domain.PaymentChannel;
import com.yamu.be.payment.domain.PaymentStatus;
import com.yamu.be.payment.domain.TransactionSource;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 결제 한 건의 상세(API 명세 V2 「결제 상세 조회」). 혜택·한도·청구 회차·환급은 각 테이블에 기록된 만큼만 보인다.
 *
 * <p>{@code selectedCard.recommendedRank}·{@code userSelected}는 승인 시작 때 결제 행에 적은 마지막 시도의 값이라 승인 응답과 같다.
 * 이 칸이 생기기 전(V20261109_4)의 결제와 SEED는 null이다.
 *
 * <p>임시: 아래 값은 아직 null이다. {@code recommendationSnapshot},
 * 혜택의 {@code expectedAmount}·{@code confirmed}·{@code confirmedAt}(예상·확정 금액을 나눠 저장하는 칸 없음).
 *
 * @param amount            POS가 요청한 결제 금액
 * @param chargedAmount     승인 금액(체크카드는 즉시할인 뒤 출금액). 승인 전·거절은 null
 * @param approvalNo        승인번호({@code ApprovalNumbers}). 승인되지 않았으면 null
 * @param installmentPolicy 할부 회차가 모두 수수료 0원으로 기록된 YAMU 할부면 {@code YAMU_INTEREST_FREE}, 그 밖은 null
 * @param memo              사용자 메모(S15P31E103-579). 목록 {@code items[].memo}와 같은 값이고, 없으면 null이다(칸은 항상 내려간다).
 *                          남기거나 지운 시각은 메모 API 응답의 {@code memoUpdatedAt}에만 있다
 */
public record PaymentDetailResponse(
        Long paymentId,
        TransactionSource source,
        PaymentStatus status,
        PaymentChannel channel,
        PaymentListResponse.Merchant merchant,
        long amount,
        PaymentPlan plan,
        Integer installmentMonths,
        Long chargedAmount,
        SelectedCard selectedCard,
        String approvalNo,
        LocalDateTime approvedAt,
        LocalDateTime canceledAt,
        List<Benefit> benefits,
        Performance performance,
        List<LimitImpact> limitImpacts,
        List<BillItem> billItems,
        List<Refund> refunds,
        Object recommendationSnapshot,
        List<StatusChange> statusHistory,
        Failure failure,
        String installmentPolicy,
        String memo) {

    public static final String YAMU_INTEREST_FREE = "YAMU_INTEREST_FREE";

    /**
     * @param maskedNumber    끝 4자리만 보이는 카드번호
     * @param recommendedRank 마지막 시도 카드의 추천 순위(1부터). 추천 밖 카드를 직접 골랐거나 기록 전 결제면 null
     * @param userSelected    마지막 시도의 카드를 사용자가 직접 골랐는지. 기록 전 결제·SEED는 null
     * @param issuer          카드사 코드·이름·대표색(앱 칸, S15P31E103-389). 목록의 {@code card.issuer}와 같은 값이다
     */
    public record SelectedCard(Long userCardId, String nickname, String maskedNumber, CardType cardType,
            Integer recommendedRank, Boolean userSelected, CardIssuers.Issuer issuer) {}

    /**
     * @param benefitId    적용한 혜택 정책 버전({@code card_benefit_id})
     * @param type         DB 혜택 종류(DISCOUNT·CASHBACK·POINT)
     * @param actualAmount 적용한 혜택의 원화 금액. 포인트처럼 원화로 바꾸지 못했으면 null
     * @param realization  혜택 반영 시점(AT_APPROVAL·AT_BILLING·AFTER_BILLING·POINT_ACCRUAL). 약관 미확인은 null
     */
    public record Benefit(Long paymentBenefitId, Long benefitId, String name, String type, Long expectedAmount,
            Long actualAmount, String realization, Boolean confirmed, LocalDateTime confirmedAt) {}

    /**
     * @param yearMonth  실적을 합산하는 월(한국 기준, YYYY-MM)
     * @param recognized 승인 상태이고 인정액이 0원보다 큰지
     * @param recognizedTotalAfter 그 카드·그 실적 월의 지금 누적 실적(앱 칸, S15P31E103-390). 카드 목록 API의 실적과 같은 값이다.
     *                             결제 당시 값이 아니라 지금 값이라 그 뒤의 결제·취소가 반영된다. 승인 상태가 아니면 null
     * @param targetAmount         목표 실적. 카드 목록 API와 같은 규칙. 실적 조건이 없는 카드·승인 상태가 아니면 null
     */
    public record Performance(String yearMonth, long recognizedAmount, boolean recognized, String exclusionReason,
            Long recognizedTotalAfter, Long targetAmount) {}

    /**
     * @param period         PER_TRANSACTION · DAILY · MONTHLY
     * @param yearMonth      사용 월(한국 기준, YYYY-MM)
     * @param consumedAmount 이 결제로 줄어든 한도. 단위는 {@code unit}을 따른다
     * @param unit           한도 단위({@code limit_group.limit_unit}): KRW 원 · POINT 포인트 · COUNT 횟수
     * @param restoredAt     취소로 한도를 되돌린 시각. 되돌리지 않았으면 null
     */
    public record LimitImpact(Long limitGroupId, String name, String period, String yearMonth, long consumedAmount,
            String unit, LocalDateTime restoredAt) {}

    /**
     * 신용카드 청구 회차.
     *
     * @param seq            회차(일시불은 1)
     * @param amount         원금 + 확정 수수료. 수수료 미산정이면 null
     * @param billId         배정된 청구서. 청구서가 만들어지기 전이면 null
     * @param billingMonth   청구 월(YYYY-MM)
     * @param withdrawalDate 청구서의 출금 예정일. 청구서가 없으면 null
     * @param feeAmount      회차 수수료. 미산정 null, 무이자 0
     */
    public record BillItem(Long billItemId, int seq, Long amount, Long billId, String billingMonth,
            LocalDate withdrawalDate, String status, long principalAmount, Long feeAmount) {}

    /** 출금 뒤 취소로 생긴 환급. @param dueDate 한국 날짜 환급 기한 */
    public record Refund(Long refundId, Long billItemId, long principalAmount, long feeAmount, long refundAmount,
            LocalDate dueDate, String status, LocalDateTime completedAt) {}

    /** 상태가 바뀐 시각. 처리 중·거절·만료는 시각을 저장하지 않아 {@code at}이 null이다(임시). */
    public record StatusChange(PaymentStatus status, LocalDateTime at) {}

    /** 거절 사유. {@code code}는 {@code FailureCode}의 이름이다. */
    public record Failure(String code, String message) {}
}
