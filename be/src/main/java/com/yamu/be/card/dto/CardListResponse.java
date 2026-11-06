package com.yamu.be.card.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.yamu.be.card.domain.CardIssuerStatus;
import com.yamu.be.card.domain.CardRegistrationStatus;
import com.yamu.be.card.domain.CardType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 보유 카드 목록(명세 「보유 카드 목록 조회」). 사용자가 정한 순서대로, 등록 해제 카드는 빠진다.
 *
 * @param dataSyncedAt 시드 거래 동기화 시각. 동기화 기록이 아직 없어 null
 */
public record CardListResponse(List<CardSummary> cards, LocalDateTime dataSyncedAt) {

    /**
     * @param nickname       별칭. 정하지 않았으면 상품명
     * @param maskedNumber   앞 4자리·끝 4자리만 보인다(예: 9410-****-****-5678)
     * @param mainBenefits   주요 혜택 문구(BE 3 혜택 이름). 임시: 필요한 실적 낮은 순 → 혜택률 높은 순 최대 3개. 오늘 유효한 혜택이 없으면 빈 목록
     * @param performance    당월(한국 기준) 실적. 집계된 값이 없으면 null(명세)
     * @param billingSummary 신용카드만. 체크카드는 null
     * @param imageUrl       카드 앞면 CDN URL. 이미지 미수집·잘못된 키는 null
     */
    public record CardSummary(Long userCardId, String nickname, Issuer issuer, String productName,
            CardType cardType, String maskedNumber, CardRegistrationStatus status, CardIssuerStatus issuerStatus,
            @JsonProperty("isDefault") boolean isDefault, List<String> mainBenefits, Performance performance,
            BillingSummary billingSummary, String imageUrl) {
    }

    public record Issuer(String code, String name, String color) {
    }

    /**
     * @param targetAmount     목표 실적. 임시: 혜택의 최소 전월 실적·연결 한도의 실적 구간 하한 중 이번 달 실적보다 큰 가장 작은 값.
     *                         다 넘었으면 가장 큰 기준. 기준이 없으면 null
     * @param achievementRate  달성률(%) = 실적 ÷ 목표 × 100, 소수 한 자리에서 버림, 최대 100. 목표가 없으면 null
     * @param remainingAmount  남은 금액 = 목표 − 실적, 0 아래로 내려가지 않음. 목표가 없으면 null
     */
    public record Performance(String yearMonth, Long targetAmount, Long recognizedAmount, Double achievementRate,
            Long remainingAmount) {

        private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

        public static Performance of(String yearMonth, Long targetAmount, long recognizedAmount) {
            if (targetAmount == null || targetAmount <= 0) {
                return new Performance(yearMonth, null, recognizedAmount, null, null);
            }
            BigDecimal rate = BigDecimal.valueOf(recognizedAmount).multiply(HUNDRED)
                    .divide(BigDecimal.valueOf(targetAmount), 1, RoundingMode.DOWN).min(HUNDRED);
            return new Performance(yearMonth, targetAmount, recognizedAmount, rate.doubleValue(),
                    Math.max(0, targetAmount - recognizedAmount));
        }
    }

    /**
     * @param accumulatingAmount 진행 중 이용기간(오늘이 들어가는 기간)에 쌓인 금액(원). BE 2 청구서의 예상 청구액이고 쓴 돈이 없으면 0.
     *                           결제일이 없거나 청구 규칙이 받지 않는 값이면 null
     * @param hasUnpaidBill      미납 청구서가 있는지
     */
    public record BillingSummary(Integer paymentDay, Long accumulatingAmount, boolean hasUnpaidBill) {
    }
}
