package com.yamu.be.card.dto;

import com.yamu.be.card.domain.CardType;
import java.time.LocalDateTime;

/**
 * 카드 확인 결과. 앱은 이 값으로 "이 카드가 맞나요?" 화면을 띄운 뒤 본인 인증(CARD_REGISTER)으로 넘어간다.
 *
 * @param verificationToken 카드 등록에 넘길 1회용 토큰(5분)
 * @param holderMatched     사용자 명의 카드인지. 명의가 다르면 CARD_NOT_FOUND로 끝나므로 응답이 오면 항상 true
 * @param hasPriorHistory   등록 이전 모의 거래(시드)가 있어 전월 실적을 가져올 수 있는지(FR-070)
 * @param issuerAccount     카드사가 정한 출금 계좌(신용카드 결제계좌·체크카드 연결 계좌). 등록하면 이 계좌로 연결된다
 * @param paymentDay        카드사가 정한 결제일. 신용카드만, 체크카드는 null
 * @param alreadyRegistered 이미 등록한 카드인지. 등록한 카드는 CARD_ALREADY_REGISTERED로 끝나므로 응답이 오면 항상 false
 */
public record CardLookupResponse(String verificationToken, LocalDateTime expiresAt, IssuerResponse issuer,
        Product product, String maskedNumber, boolean holderMatched, boolean hasPriorHistory,
        IssuerAccount issuerAccount, Integer paymentDay, boolean alreadyRegistered) {

    /** @param closingDayOffset 이용기간 마감일 = 결제일 − N일의 N. 신용카드만, 체크카드는 null */
    public record IssuerResponse(String code, String name, String color, Integer closingDayOffset) {
    }

    public record Product(Long cardProductId, String name, CardType cardType) {
    }

    /**
     * @param linkedAccountId 이 계좌와 같은, 사용자가 연결한 계좌. 아직 연결하지 않았으면 null
     * @param linked          지금 연결(CONNECTED) 상태인지. false면 등록 전에 이 계좌를 먼저 연결해야 한다(CARD_ACCOUNT_NOT_LINKED)
     */
    public record IssuerAccount(String bankCode, String maskedAccountNo, Long linkedAccountId, boolean linked) {
    }
}
