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
 */
public record CardDetailResponse(Long userCardId, String nickname, Issuer issuer, String productName,
        CardType cardType, String maskedNumber, LocalDate expiryDate, CardRegistrationStatus status,
        CardIssuerStatus issuerStatus, @JsonProperty("isDefault") boolean isDefault, LinkedAccount linkedAccount,
        Billing billing, LocalDateTime registeredAt, LocalDateTime dataSyncedAt) {

    /** @param closingDayOffset 이용기간 마감일 = 결제일 − N일의 N. 신용카드만 */
    public record Issuer(String code, String name, String color, Integer closingDayOffset) {
    }

    /**
     * 체크카드 연결 계좌.
     *
     * @param bankCode    은행 코드(예: 088). 은행 이름 표가 없어 코드로 준다
     * @param balance     잔액. 계좌 잔액 조회(계획 10번)가 붙기 전이라 null
     * @param balanceAsOf 잔액 조회 시각. 지금은 null
     */
    public record LinkedAccount(Long accountId, String bankCode, String maskedAccountNo, Long balance,
            LocalDateTime balanceAsOf) {
    }

    /**
     * 신용카드 청구 정보.
     *
     * @param paymentDayChangeableFrom 결제일을 다시 바꿀 수 있는 날(직전 변경 후 3개월, 한국 날짜). 바꾼 적 없으면 null(지금 바꿀 수 있음)
     * @param creditLimit              카드 한도. 저장 칸이 없어 null
     * @param availableLimit           남은 한도. 한도가 없어 null
     * @param currentCycle             진행 중 이용기간. 이용기간·청구 계산(BE 2)이 아직 없어 null
     * @param nextWithdrawal           출금일이 가장 가까운 확정 청구서. 없으면 null
     */
    public record Billing(Integer paymentDay, LocalDate paymentDayChangeableFrom, Long settlementAccountId,
            Long creditLimit, Long availableLimit, CurrentCycle currentCycle, NextWithdrawal nextWithdrawal,
            boolean hasUnpaidBill) {
    }

    public record CurrentCycle(UsagePeriod usagePeriod, Long accumulatingAmount, LocalDate confirmAt) {
    }

    public record UsagePeriod(LocalDate start, LocalDate end) {
    }

    public record NextWithdrawal(Long billId, Long amount, LocalDate withdrawalDate, String status) {
    }
}
