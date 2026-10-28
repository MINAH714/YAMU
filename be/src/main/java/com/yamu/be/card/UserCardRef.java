package com.yamu.be.card;

import com.yamu.be.card.domain.CardIssuerStatus;
import com.yamu.be.card.domain.CardRegistrationStatus;

/**
 * 다른 기능에 넘기는 보유 카드 정보. 엔티티 대신 이 값을 넘겨 트랜잭션 밖에서도 안전하게 쓴다.
 *
 * <p>정지·만료 카드를 거절할지는 받는 쪽이 {@code issuerStatus}를 보고 정한다(예: 예정 지출은 거절).
 *
 * @param userCardId      보유 카드 번호
 * @param cardProductId   카드 상품 번호
 * @param linkedAccountId 출금 계좌 번호. 없으면 null
 * @param cardLast4       화면 표시용 카드번호 끝 4자리
 * @param registrationStatus YAMU 등록 상태. 여기로 오는 카드는 ACTIVE 또는 INACTIVE
 * @param issuerStatus    카드사가 정한 상태(NORMAL / SUSPENDED / EXPIRED)
 */
public record UserCardRef(
        Long userCardId,
        Long cardProductId,
        Long linkedAccountId,
        String cardLast4,
        CardRegistrationStatus registrationStatus,
        CardIssuerStatus issuerStatus) {
}
