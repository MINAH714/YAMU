package com.yamu.be.forecast;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;

/**
 * 선택 계좌의 월별 유효 생활 소비 합계를 읽는다. 잔고예보의 생활비 추정(명세 「생활비 예측 상세」 AVG_3M) 입력이다.
 *
 * <p>임시 계약: 원천은 YAMU 체크카드 LIVING 소비(BE 2 결제)와 생활비로 확인된 SSAFY 계좌 출금(BE 1 거래내역)이다.
 * 둘 다 아직 코드에 없어 잔고예보가 필요한 모양을 먼저 정했다. 원천을 붙일 때 담당자와 맞춘다.
 *
 * <p>구현이 지켜야 할 것:
 * <ul>
 *   <li><b>정제된 데이터만</b>: 같은 소비가 체크카드 결제와 계좌 출금에 두 번 잡히면 한 번으로 줄인다(중복 제거).
 *       소비 분류가 LIVING으로 끝난 것만 더한다. 예정 지출로 따로 반영하는 정기 지출(월세·통신비·구독료),
 *       출금일에 따로 반영하는 신용카드 청구, 본인 계좌 간 이체, 큰 일회성 지출, 목적을 알 수 없는 출금은 뺀다.</li>
 *   <li><b>완전성</b>: {@code complete=true}는 그달(이번 달은 오늘까지)의 거래를 빠짐없이 확인했다는 뜻이다.
 *       그때 {@code amount=0}은 실제로 0원을 쓴 달이다. 거래를 다 확인하지 못했으면 {@code complete=false}로 주고,
 *       아예 모르는 달은 행을 주지 않는다. 잔고예보는 두 경우를 모두 자료 누락으로 본다.</li>
 *   <li>한 계좌·한 달·한 출처(REAL/SEED)마다 행은 하나다. 계좌 귀속이 없는 소비를 여러 계좌에 복제하거나 나눠 담지 않는다.</li>
 *   <li>남의 계좌는 호출 전에 걸러진다(잔액 조회가 404로 거절). 원천 실패는 금융망 예외(502·504)를 그대로 던진다.</li>
 * </ul>
 */
interface LivingCostReader {

    /**
     * fromMonth~toMonth(포함)의 계좌별·월별 생활 소비 합계를 돌려준다. 이번 달은 오늘까지의 합계다.
     *
     * @return 월별 합계와 원천을 읽은 시각
     */
    Spending monthlySpending(Long userId, List<Long> accountIds, YearMonth fromMonth, YearMonth toMonth);

    /**
     * @param months   계좌·월·출처별 합계
     * @param syncedAt 원천을 읽은 UTC 시각
     */
    record Spending(List<MonthlySpending> months, LocalDateTime syncedAt) {}

    /**
     * @param amount   그달 유효 생활 소비 합계(원, 0 이상)
     * @param origin   실제 거래(REAL)인지 모의 소비(SEED)인지
     * @param complete 그달 거래를 빠짐없이 확인했는지. false면 자료 누락으로 본다
     */
    record MonthlySpending(Long accountId, YearMonth month, long amount, Origin origin, boolean complete) {}

    enum Origin { REAL, SEED }
}
