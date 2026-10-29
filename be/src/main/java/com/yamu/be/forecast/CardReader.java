package com.yamu.be.forecast;

import java.util.Optional;

/**
 * 예정 지출에 지정한 카드의 출금 정보를 읽는다.
 *
 * <p>임시 계약: BE 1이 {@code UserCardRef}에 cardType·paymentDay·billingCutoffDays를 추가하기로 했다(2026-10-29 합의).
 * 그 코드가 머지되면 {@code CardQueryService}를 직접 쓰고 이 인터페이스는 지운다.
 * 그쪽 조회는 등록 해제 카드를 404로 던지므로, 바꿀 때 예보 전체가 실패하지 않게 처리를 맞춘다.
 */
interface CardReader {

    /**
     * 사용자의 등록된 카드 정보를 돌려준다. 없거나 등록 해제된 카드면 empty다.
     *
     * @return 카드 출금 정보
     */
    Optional<CardInfo> findRegisteredCard(Long userId, Long userCardId);

    /**
     * 출금 계산에 필요한 카드 정보.
     *
     * @param linkedAccountId   출금 계좌. 없으면 null
     * @param cardType          체크(즉시 출금)·신용(결제일 출금)
     * @param paymentDay        신용카드 월 결제일. 체크카드는 null
     * @param billingCutoffDays 결제일에서 뺄 이용기간 마감 일수. 없으면 null
     */
    record CardInfo(Long userCardId, Long linkedAccountId, CardType cardType, Integer paymentDay,
            Integer billingCutoffDays) {}

    enum CardType { CHECK, CREDIT }
}
