package com.yamu.be.home;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 금융 데이터 동기화({@code POST /api/sync}) 결과. 모양은 앱 {@code SyncDto}(fe/android)를 따른다.
 *
 * @param syncedAt        동기화한 UTC 시각. 30초 안에 다시 부르면 처음 동기화한 시각 그대로다
 * @param newTransactions 새로 받아온 거래 수. 서버가 금융망 거래내역을 저장하지 않아 항상 0이다
 * @param failed          잔액을 새로 읽지 못한 계좌. 다 읽었으면 빈 목록
 */
public record SyncResponse(LocalDateTime syncedAt, int newTransactions, List<Failure> failed) {

    /** @param code 금융망 실패 코드(FIN_API_UNAVAILABLE·FIN_API_ERROR·UPSTREAM_TIMEOUT 등) */
    public record Failure(Long accountId, String code) {}
}
