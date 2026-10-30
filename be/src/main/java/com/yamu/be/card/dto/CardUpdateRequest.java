package com.yamu.be.card.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Size;

/**
 * 카드 정보 수정(명세 「카드 정보 수정」). 보낸 필드만 바꾸고, 보내지 않았거나 null인 필드는 그대로 둔다.
 *
 * @param nickname            별칭. 빈 문자열이면 지운다(화면은 상품명)
 * @param status              ACTIVE 추천 포함 · INACTIVE 추천 제외
 * @param isDefault           true면 이 카드를 기본 카드로(기존 기본 카드는 풀림), false면 풂
 * @param paymentDay          신용카드 결제일 1~27. 직전 변경 후 3개월 안에는 거부
 * @param settlementAccountId 신용카드 결제계좌
 */
public record CardUpdateRequest(
        @Size(max = 20, message = "카드 별칭은 20자까지입니다.")
        String nickname,
        String status,
        @JsonProperty("isDefault") Boolean isDefault,
        Integer paymentDay,
        Long settlementAccountId) {
}
