package com.yamu.be.forecast;

import java.time.LocalDate;
import java.util.List;

/**
 * 선택 계좌가 출금 계좌인 사용자 신용카드와, 카드별 일별 유효 생활 소비(LIVING) 합계를 읽는다.
 * 오늘부터 이번 이용기간 마감까지 쓸 신용카드 금액을 추정하는 입력이다({@link CreditSpendingEstimator}, S15P31E103-286).
 *
 * <p>규칙은 체크카드 생활비 원천({@link LivingCostReader})과 같다. 승인·LIVING 결제만 더하고, 매달 한 번 나오는 반복 지출은 빼며,
 * 출처(REAL/SEED)마다 그 출처가 덮는 기간의 모든 날에 줄을 준다(덮지 않는 날은 줄이 없다). 구현은 같은 규칙을 함께 쓰려고
 * {@link PaymentLivingCostReader}에 있다(2026-11-06 결정).
 */
interface CreditSpendingReader {

    /**
     * fromDate~toDate(포함, 한국 날짜)의 신용카드별·일별 생활 소비 합계를 돌려준다.
     *
     * @param accountIds 선택 계좌. 카드의 지금 출금 계좌가 이 안에 있는 신용카드만 읽는다
     * @return 대상 카드와 일별 합계
     */
    CreditSpending creditDailySpending(Long userId, List<Long> accountIds, LocalDate fromDate, LocalDate toDate);

    /**
     * @param userCardIds 선택 계좌가 출금 계좌인 사용자 신용카드(등록 상태와 상관없음. 쓸 수 있는 카드인지는 부르는 쪽이 BE 1로 확인한다)
     * @param days        카드·날짜·출처별 합계. 모두 complete=true다
     */
    record CreditSpending(List<Long> userCardIds, List<CardDailySpending> days) {}

    /**
     * @param amount 그날 그 카드의 유효 생활 소비 합계(원, 0 이상)
     */
    record CardDailySpending(long userCardId, LocalDate date, long amount, LivingCostReader.Origin origin) {}
}
