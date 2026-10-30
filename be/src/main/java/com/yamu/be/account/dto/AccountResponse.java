package com.yamu.be.account.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 연결 계좌 한 개(명세 「계좌 목록 조회」의 accounts[]). 「계좌 정보 수정」의 응답도 이 형식이다.
 *
 * @param accountName         사용자가 지은 별칭. 없으면 null
 * @param balance             SSAFY 현재 잔액. 금융망을 못 읽었으면 null
 * @param balanceAsOf         잔액을 읽은 UTC 시각(캐시면 캐시를 채운 시각). 잔액이 null이면 null
 * @param isPrimary           기준 계좌(홈 기본 계좌 미리보기). 잔고예보 선택 계좌와는 별개
 * @param linkedCards         이 계좌를 출금 계좌로 쓰는 등록 카드
 * @param upcomingWithdrawals 이 계좌에서 나갈 확정 청구서(card_bill CONFIRMED). 출금일 순
 * @param linkedAt            최근 연결 시각
 */
public record AccountResponse(Long accountId, BankResponse bank, String accountName, String maskedAccountNo,
        Long balance, LocalDateTime balanceAsOf, @JsonProperty("isPrimary") boolean isPrimary,
        List<LinkedCard> linkedCards, List<UpcomingWithdrawal> upcomingWithdrawals, LocalDateTime linkedAt) {

    /** @param role CHECK_LINKED 체크카드 즉시 출금 계좌 · CREDIT_SETTLEMENT 신용카드 청구액 출금 계좌 */
    public record LinkedCard(Long userCardId, String nickname, String role) {
    }

    public record UpcomingWithdrawal(Long billId, Long amount, LocalDate withdrawalDate) {
    }
}
