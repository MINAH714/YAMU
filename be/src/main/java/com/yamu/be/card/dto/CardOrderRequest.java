package com.yamu.be.card.dto;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * 카드 고정 정렬 순서 저장(명세 「카드 고정 정렬 순서 저장」). 등록된(ACTIVE·INACTIVE) 카드 전체를 원하는 순서로 보낸다.
 * 빠지거나 겹치거나 남의 카드가 섞이면 400 ORDER_INCOMPLETE다.
 */
public record CardOrderRequest(
        @NotNull(message = "카드 순서가 필요합니다.")
        List<Long> userCardIds) {
}
