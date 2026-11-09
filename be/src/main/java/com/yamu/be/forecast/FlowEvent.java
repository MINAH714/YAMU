package com.yamu.be.forecast;

import java.time.LocalDate;

/**
 * 잔고예보에 반영할 미래 입출금 하나. 돈이 실제로 들어오거나 나가는 계좌와 날짜에 둔다.
 *
 * @param accountId  입금·출금 계좌(연결 계좌 번호)
 * @param date       한국 날짜 기준 입출금 예정일
 * @param amount     입금은 양수, 출금은 음수(원)
 * @param type       예정 수입·예정 지출·카드 청구·가상 구매 출금·마감 전 신용카드 사용 추정
 * @param confidence 확정 선부터 반영(CONFIRMED)인지, 예상 선부터(EXPECTED)인지, 보수적 선에만(CONSERVATIVE)인지
 * @param title      화면에 보일 이름
 * @param refId      원본 참조(예정 항목 ID, 청구서 ID). 가상 구매는 저장된 원본이 없어 null
 */
public record FlowEvent(Long accountId, LocalDate date, long amount, Type type, Confidence confidence,
        String title, Long refId) {

    /**
     * SIMULATED_PURCHASE는 가상 구매 비교({@link PurchaseSimulator})의 구매 후 선에만 넣는다.
     * CARD_SPENDING_ESTIMATE는 내일부터 이번 이용기간 마감까지 쓸 신용카드 금액의 추정({@link CreditSpendingEstimator})이고
     * 늘 CONSERVATIVE다.
     */
    public enum Type { SCHEDULED_INCOME, SCHEDULED_OUTFLOW, CARD_BILLING, SIMULATED_PURCHASE, CARD_SPENDING_ESTIMATE }

    /** CONSERVATIVE는 보수적 선에만 반영하는 추정 출금이다. 확정·예상 선은 그대로다. */
    public enum Confidence { CONFIRMED, EXPECTED, CONSERVATIVE }
}
