package com.yamu.be.card.dto;

import java.time.LocalDateTime;
import java.util.List;

/** 저장한 카드 순서. 카드 목록 표시 순서이자 추천 동률의 마지막 기준(FIXED_ORDER)이다. */
public record CardOrderResponse(List<Long> fixedOrder, LocalDateTime updatedAt) {
}
