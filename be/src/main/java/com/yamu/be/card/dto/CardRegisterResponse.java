package com.yamu.be.card.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.yamu.be.card.domain.CardRegistrationStatus;
import com.yamu.be.card.domain.CardType;
import java.time.LocalDateTime;

/**
 * 카드 등록 결과(201).
 *
 * @param nickname           카드 별칭. 정하지 않았으면 상품명
 * @param isDefault          기본 카드인지. 첫 카드라도 자동 지정하지 않는다(카드 추천 정책 8장). 지금은 항상 false
 * @param linkedAccountId    출금 계좌(신용카드는 결제계좌, 체크카드는 연결 계좌)
 * @param billing            신용카드 청구 정보. 체크카드는 null
 * @param initialPerformance 등록 이전 실적. 집계된 값이 없으면 source NONE
 * @param imageUrl           카드 앞면 CDN URL. 이미지 미수집·잘못된 키는 null
 */
public record CardRegisterResponse(Long userCardId, String nickname, Issuer issuer, String productName,
        CardType cardType, String maskedNumber, CardRegistrationStatus status,
        @JsonProperty("isDefault") boolean isDefault, Long linkedAccountId, Billing billing,
        InitialPerformance initialPerformance, LocalDateTime registeredAt, String imageUrl) {

    public record Issuer(String code, String name, String color) {
    }

    /** @param creditLimit 카드 한도. 저장 칸이 없어 지금은 null */
    public record Billing(Integer paymentDay, Integer closingDayOffset, Long settlementAccountId, Long creditLimit) {
    }

    /**
     * @param source SEED 시드로 집계된 실적이 있음 · NONE 없음(새 카드). NONE이면 앱은 "확인 불가"로 보여 주고 0으로 쓰지 않는다
     */
    public record InitialPerformance(String source, Month prevMonth, Month currentMonth) {

        public static InitialPerformance none() {
            return new InitialPerformance("NONE", null, null);
        }
    }

    /** @param benefitUsedAmount 이번 달 혜택 사용량. 한도 단위가 금액·횟수로 섞여 있어 지금은 null */
    public record Month(String yearMonth, Long recognizedAmount, Long benefitUsedAmount) {
    }
}
