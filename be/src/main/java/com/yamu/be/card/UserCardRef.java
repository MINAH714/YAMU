package com.yamu.be.card;

import com.yamu.be.card.domain.CardIssuerStatus;
import com.yamu.be.card.domain.CardRegistrationStatus;
import com.yamu.be.card.domain.CardType;

/**
 * 다른 기능에 넘기는 보유 카드 정보. 엔티티 대신 이 값을 넘겨 트랜잭션 밖에서도 안전하게 쓴다.
 *
 * <p>정지·만료 카드를 거절할지는 받는 쪽이 {@code issuerStatus}를 보고 정한다(예: 예정 지출은 거절).
 * 잔고예보는 {@code cardType}·{@code paymentDay}·{@code billingCutoffDays}로 출금일을 정한다(체크: 예정일, 신용: 결제일).
 *
 * @param userCardId         보유 카드 번호
 * @param cardProductId      카드 상품 번호
 * @param linkedAccountId    출금 계좌 번호(신용카드 결제계좌 또는 체크카드 연결 계좌). 없으면 null
 * @param cardLast4          화면 표시용 카드번호 끝 4자리
 * @param registrationStatus YAMU 등록 상태. 여기로 오는 카드는 ACTIVE 또는 INACTIVE
 * @param issuerStatus       카드사가 정한 상태(NORMAL / SUSPENDED / EXPIRED)
 * @param cardType           카드 종류(CREDIT / CHECK)
 * @param paymentDay         신용카드 결제일(1~27). 체크카드는 null
 * @param billingCutoffDays  신용카드 이용기간 마감일 = 결제일 − N일의 N. 체크카드·미설정은 null
 */
public record UserCardRef(
        Long userCardId,
        Long cardProductId,
        Long linkedAccountId,
        String cardLast4,
        CardRegistrationStatus registrationStatus,
        CardIssuerStatus issuerStatus,
        CardType cardType,
        Integer paymentDay,
        Integer billingCutoffDays) {

    /** 카드 종류·결제일을 쓰지 않는 기존 호출부(테스트 포함)용. 추가한 세 값은 null이다. */
    public UserCardRef(Long userCardId, Long cardProductId, Long linkedAccountId, String cardLast4,
            CardRegistrationStatus registrationStatus, CardIssuerStatus issuerStatus) {
        this(userCardId, cardProductId, linkedAccountId, cardLast4, registrationStatus, issuerStatus, null, null, null);
    }
}
