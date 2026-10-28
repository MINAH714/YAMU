package com.yamu.be.card.domain;

/**
 * YAMU 등록 상태(DB {@code user_card.status}). 사용자가 YAMU에 등록해 추천·결제에 쓰는지를 나타낸다.
 * 카드사가 정한 정지·만료는 {@link CardIssuerStatus}.
 */
public enum CardRegistrationStatus {
    /** 서버가 미리 준비한, 사용자 등록 전 카드 */
    PENDING,
    /** 등록 완료. 추천·결제에 사용 */
    ACTIVE,
    /** 등록은 유지하되 추천에서 제외 */
    INACTIVE,
    /** 등록 해제. 과거 거래 이력 보존용 */
    UNREGISTERED;

    /** 보유 카드 목록에 보이는 카드인지(API 명세 「보유 카드 목록 조회」). */
    public boolean isRegistered() {
        return this == ACTIVE || this == INACTIVE;
    }
}
