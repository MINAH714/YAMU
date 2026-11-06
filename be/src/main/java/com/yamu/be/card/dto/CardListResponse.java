package com.yamu.be.card.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.yamu.be.card.domain.CardIssuerStatus;
import com.yamu.be.card.domain.CardRegistrationStatus;
import com.yamu.be.card.domain.CardType;
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
     * @param mainBenefits   주요 혜택 문구. 혜택 규칙(BE 3)에서 문구를 만들 곳이 아직 없어 빈 목록
     * @param performance    당월(한국 기준) 실적. 집계된 값이 없으면 null
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
     * @param targetAmount     다음 구간 기준 실적. 구간 정보(BE 3)를 읽을 곳이 없어 null
     * @param achievementRate  달성률. 기준 실적이 없어 null
     * @param remainingAmount  남은 금액. 기준 실적이 없어 null
     */
    public record Performance(String yearMonth, Long targetAmount, Long recognizedAmount, Double achievementRate,
            Long remainingAmount) {
    }

    /**
     * @param accumulatingAmount 진행 중 이용기간(오늘이 들어가는 기간)에 쌓인 금액(원). BE 2 청구서의 예상 청구액이고 쓴 돈이 없으면 0.
     *                           결제일이 없거나 청구 규칙이 받지 않는 값이면 null
     * @param hasUnpaidBill      미납 청구서가 있는지
     */
    public record BillingSummary(Integer paymentDay, Long accumulatingAmount, boolean hasUnpaidBill) {
    }
}
