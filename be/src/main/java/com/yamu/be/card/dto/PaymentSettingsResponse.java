package com.yamu.be.card.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 결제 설정(노션 API 명세 V2 「결제 설정 조회」, DEC-004·FR-065). 결제 순서는 자동 선택(순혜택 최대) → 동률이면 기본 카드 →
 * 고정 정렬 순서이고, 자동 선택을 끄면 항상 기본 카드로 결제한다.
 *
 * @param autoSelect          결제 카드 자동 선택. false면 기본 카드로 결제
 * @param defaultUserCardId   기본 카드. 없으면 null
 * @param includedUserCardIds 자동 선택에 포함된 카드(ACTIVE), 고정 정렬 순서대로
 * @param excludedUserCardIds 자동 선택에서 뺀 카드(INACTIVE), 고정 정렬 순서대로. 카드 수정 API의 {@code status}와 같은 값
 * @param fixedOrder          등록된 카드 전체의 고정 정렬 순서(동률 마지막 기준). {@code PUT /api/cards/order}로 바꾼다
 * @param updatedAt           결제 설정에 쓰는 값(자동 선택·등록된 카드)이 마지막으로 바뀐 시각(UTC). 임시: 카드 별칭 수정처럼
 *                            결제 설정과 상관없는 카드 수정도 이 시각을 바꾼다
 */
public record PaymentSettingsResponse(boolean autoSelect, Long defaultUserCardId, List<Long> includedUserCardIds,
        List<Long> excludedUserCardIds, List<Long> fixedOrder, LocalDateTime updatedAt) {
}
