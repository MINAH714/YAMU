package com.yamu.be.forecast;

import java.time.LocalDate;
import java.util.List;

/**
 * 선택 계좌에서 아직 출금되지 않은 신용카드 청구를 읽는다. 구현은 BE 2가 card_bill을 읽어 만든다.
 *
 * <p>상태별 계약(2026-10-30 BE 2 확인):
 * <ul>
 *   <li>ACCUMULATING(이용기간 진행 중): 금액은 지금까지 쌓인 회차 합계 − 예상 청구할인, 계좌는 카드의 현재 출금 계좌,
 *       날짜는 이번 이용기간의 출금 예정일.</li>
 *   <li>CONFIRMED(청구 확정): 금액은 확정 청구액, 계좌는 확정 당시 출금 계좌, 날짜는 card_bill.withdrawal_date.
 *       출금 결과가 아직 확정되지 않았으면(금융망 결과 불명 포함) 출금일이 지났어도 넣는다.</li>
 *   <li>UNPAID(출금 실패·미납): 남은 미납액, 확정 당시 출금 계좌, 원래 출금일. 재출금 일정이 없어 예보는 차감하지 않고 알리기만 한다.</li>
 *   <li>PAID(출금 완료)는 넣지 않는다(이미 현재 잔액에 반영됨).</li>
 * </ul>
 * 출금일은 결제일이 비영업일이면 이미 다음 영업일로 옮긴 값이다. accountIds에 없는 계좌의 청구는 넣지 않는다.
 * 출금 계좌·상태·출금일은 비지 않는다(비면 잔고예보는 계약 위반으로 실패한다).
 */
public interface CardBillReader {

    /**
     * 출금일이 to(포함) 이전인 ACCUMULATING·CONFIRMED 청구와 모든 UNPAID 청구를 돌려준다.
     *
     * @return 아직 출금되지 않은 청구
     */
    List<OutstandingBill> outstanding(Long userId, List<Long> accountIds, LocalDate to);

    enum Status { ACCUMULATING, CONFIRMED, UNPAID }

    /**
     * @param accountId      출금 계좌
     * @param withdrawalDate 한국 날짜 기준 출금 예정일(UNPAID는 원래 출금일)
     * @param amount         출금할 금액(원, 0 이상). UNPAID는 남은 미납액
     */
    record OutstandingBill(Long cardBillId, Long accountId, Status status, LocalDate withdrawalDate, long amount) {}
}
