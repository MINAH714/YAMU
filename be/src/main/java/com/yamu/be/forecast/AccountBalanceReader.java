package com.yamu.be.forecast;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 선택 계좌의 현재 잔액을 읽는다.
 *
 * <p>임시 계약: BE 1이 같은 모양의 {@code AccountBalanceService.getBalances}를 제공하기로 했다(2026-10-29 합의).
 * 그 코드가 머지되면 직접 호출로 바꾸고 이 인터페이스는 지운다. 합의된 동작:
 * <ul>
 *   <li>하나라도 남의 계좌·연결 해제 계좌면 전체를 404 {@code RESOURCE_NOT_FOUND}로 거절한다.</li>
 *   <li>금융망 실패는 일부 계좌만 실패해도 전체를 공통 클라이언트 예외(502·504)로 던진다.</li>
 *   <li>10분 캐시는 BE 1이 둔다. refresh=true면 캐시를 건너뛴다.</li>
 * </ul>
 */
interface AccountBalanceReader {

    /**
     * 계좌별 잔액과 조회 시각을 돌려준다.
     *
     * @return 계좌별 잔액
     */
    List<AccountBalance> getBalances(Long userId, List<Long> accountIds, boolean refresh);

    /**
     * @param balance   현재 잔액(원)
     * @param fetchedAt 금융망에서 읽은 UTC 시각
     */
    record AccountBalance(Long linkedAccountId, long balance, LocalDateTime fetchedAt) {}
}
