package com.yamu.be.forecast;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 선택 계좌의 일별 유효 생활 소비 합계를 읽는다. 잔고예보의 생활비 추정(명세 「생활비 예측 상세」 AVG_3M, 이후 AI 예측) 입력이다.
 *
 * <p>임시 계약: 원천은 YAMU 체크카드 LIVING 소비(BE 2 결제)다. 명세의 "생활비로 확인된 SSAFY 계좌 출금"은 확인 방법이
 * 정의되지 않아 지금은 넣지 않는다(docs/be/forecast.md 「계좌 출금은 지금 넣지 않음」).
 * 원천 구현이 아직 없어 잔고예보가 필요한 모양을 먼저 정했다. 원천을 붙일 때 담당자와 맞춘다(docs/be/forecast.md 「생활비 원천 계약」).
 *
 * <p>구현이 지켜야 할 것:
 * <ul>
 *   <li><b>정제된 데이터만</b>: 소비 분류가 LIVING으로 끝난 것만 더한다. 예정 지출로 따로 반영하는 정기 지출(월세·통신비·구독료),
 *       출금일에 따로 반영하는 신용카드 청구, 본인 계좌 간 이체, 큰 일회성 지출, 목적을 알 수 없는 출금은 뺀다.</li>
 *   <li><b>하루 한 줄</b>: fromDate~toDate의 모든 날에 줄을 준다. 쓴 게 없는 날도 {@code amount=0} 줄이다.
 *       fromDate보다 앞선 날은 주지 않는다(줘도 계산에 쓰지 않지만 음수·중복 같은 계약 위반은 똑같이 거절한다).</li>
 *   <li><b>체크카드 몫</b>: {@code checkCardAmount}는 그중 체크카드 결제 금액이다. 지금은 {@code amount}와 같다.</li>
 *   <li><b>완전성</b>: {@code complete=true}는 그날 거래를 빠짐없이 확인했다는 뜻이다. 그때 {@code amount=0}은 실제로 0원을 쓴 날이다.
 *       거래를 다 확인하지 못했으면 {@code complete=false}로 주고, 아예 모르는 날은 줄을 주지 않는다. 잔고예보는 두 경우를 모두
 *       자료 누락으로 본다.</li>
 *   <li>한 계좌·한 날짜·한 출처(REAL/SEED)마다 줄은 하나다. 계좌 귀속이 없는 소비를 여러 계좌에 복제하거나 나눠 담지 않는다.
 *       날짜는 한국 날짜이고 toDate(오늘)보다 뒤 날짜는 주지 않는다.</li>
 *   <li>남의 계좌는 호출 전에 걸러진다(잔액 조회가 404로 거절). 원천 실패는 금융망 예외(502·504)를 그대로 던진다.</li>
 * </ul>
 */
interface LivingCostReader {

    /**
     * fromDate~toDate(포함, 한국 날짜)의 계좌별·일별 생활 소비 합계를 돌려준다. toDate는 오늘이고 오늘 줄은 지금까지의 합계다.
     *
     * @return 일별 합계와 원천을 읽은 시각
     */
    Spending dailySpending(Long userId, List<Long> accountIds, LocalDate fromDate, LocalDate toDate);

    /**
     * @param days     계좌·날짜·출처별 합계
     * @param syncedAt 원천을 읽은 UTC 시각
     */
    record Spending(List<DailySpending> days, LocalDateTime syncedAt) {}

    /**
     * @param date            한국 날짜
     * @param amount          그날 유효 생활 소비 합계(원, 0 이상). 지금은 체크카드 LIVING 결제 합계다
     * @param checkCardAmount amount 중 YAMU 체크카드 LIVING 결제 몫(원, 0 이상 amount 이하). 체크카드 거래로 학습한
     *                        AI 생활비 예측의 입력이다
     * @param origin          실제 거래(REAL)인지 모의 소비(SEED)인지
     * @param complete        그날 원천 거래를 빠짐없이 확인했는지. false면 자료 누락으로 본다
     */
    record DailySpending(Long accountId, LocalDate date, long amount, long checkCardAmount, Origin origin,
            boolean complete) {}

    enum Origin { REAL, SEED }
}
