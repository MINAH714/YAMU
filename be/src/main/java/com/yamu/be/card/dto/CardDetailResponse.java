package com.yamu.be.card.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.yamu.be.card.domain.CardIssuerStatus;
import com.yamu.be.card.domain.CardRegistrationStatus;
import com.yamu.be.card.domain.CardType;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 카드 상세(명세 「카드 상세 조회」). 카드 정보 수정의 응답도 이 형식이다.
 * 체크카드는 {@code linkedAccount}, 신용카드는 {@code billing}이 채워지고 다른 쪽은 null이다.
 *
 * @param expiryDate   유효기간. 저장 칸이 없어 null
 * @param dataSyncedAt 시드 거래 동기화 시각. 동기화 기록이 아직 없어 null
 * @param imageUrl     카드 앞면 CDN URL. 이미지 미수집·잘못된 키는 null
 */
public record CardDetailResponse(Long userCardId, String nickname, Issuer issuer, String productName,
        CardType cardType, String maskedNumber, LocalDate expiryDate, CardRegistrationStatus status,
        CardIssuerStatus issuerStatus, @JsonProperty("isDefault") boolean isDefault, LinkedAccount linkedAccount,
        Billing billing, LocalDateTime registeredAt, LocalDateTime dataSyncedAt, String imageUrl) {

    /** @param closingDayOffset 이용기간 마감일 = 결제일 − N일의 N. 신용카드만 */
    public record Issuer(String code, String name, String color, Integer closingDayOffset) {
    }

    /**
     * 체크카드 연결 계좌.
     *
     * @param bankCode    은행 코드(예: 088)
     * @param bankName    은행 이름(예: 신한은행). 계좌 목록과 같은 표({@code BankNames})로 채운다
     * @param balance     잔액. 계좌 잔액 캐시(10분)에 있을 때만 채운다. 없으면 null(계좌 목록·잔고예보를 보면 채워진다)
     * @param balanceAsOf 잔액을 읽은 UTC 시각. 잔액이 null이면 null
     */
    public record LinkedAccount(Long accountId, String bankCode, String bankName, String maskedAccountNo, Long balance,
            LocalDateTime balanceAsOf) {
    }

    /**
     * 신용카드 청구 정보.
     *
     * @param paymentDayChangeableFrom 결제일을 다시 바꿀 수 있는 날(직전 변경 후 3개월, 한국 날짜). 바꾼 적 없으면 null(지금 바꿀 수 있음)
     * @param creditLimit              카드 한도. 저장 칸이 없어 null
     * @param availableLimit           남은 한도. 한도가 없어 null
     * @param currentCycle             진행 중 이용기간(오늘이 들어가는 기간). 결제일이 없거나 청구 규칙이 받지 않는 값이면 null
     * @param nextWithdrawal           출금일이 가장 가까운 확정 청구서. 없으면 null
     */
    public record Billing(Integer paymentDay, LocalDate paymentDayChangeableFrom, Long settlementAccountId,
            Long creditLimit, Long availableLimit, CurrentCycle currentCycle, NextWithdrawal nextWithdrawal,
            boolean hasUnpaidBill) {
    }

    /**
     * @param accumulatingAmount 지금까지 쌓인 금액(원). BE 2 청구서의 예상 청구액(청구 전 회차 합계 − 예상 청구할인). 쓴 돈이 없으면 0
     * @param confirmAt          청구가 확정되는 날(한국 날짜) = 이용기간 마감 다음 날
     */
    public record CurrentCycle(UsagePeriod usagePeriod, Long accumulatingAmount, LocalDate confirmAt) {
    }

    /** 이용기간(양 끝 포함, 한국 날짜) */
    public record UsagePeriod(LocalDate start, LocalDate end) {
    }

    public record NextWithdrawal(Long billId, Long amount, LocalDate withdrawalDate, String status) {
    }
}
