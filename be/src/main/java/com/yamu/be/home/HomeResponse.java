package com.yamu.be.home;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 홈 요약({@code GET /api/home}). 칸 이름과 모양은 앱 {@code HomeDto.kt}(fe/android)를 따른다. 앱은 모르는 칸을 무시하고
 * 없는 칸은 기본값으로 그리므로, 아직 채우지 못하는 칸은 null이나 빈 목록으로 둔다.
 *
 * <p>영역 하나가 실패해도 응답은 200이다. 실패한 영역은 null·빈 목록으로 두고 {@code partialErrors}에 적는다.
 *
 * @param cards                   등록 카드(ACTIVE·INACTIVE, 카드 목록과 같은 순서)
 * @param performanceAlerts       실적 부족 카드. 임시: S15P31E103-373(카드별 실적) 머지 뒤 연결한다. 그 전에는 빈 목록
 * @param balanceForecast         기준 계좌의 잔고예보 요약. 기준 계좌가 없으면 null
 * @param monthSummary            이번 달 소비·혜택. 임시: S15P31E103-372(월간 리포트) 머지 뒤 연결한다. 그 전에는 null
 * @param unreadNotificationCount 읽지 않은 알림 수
 * @param partialErrors           실패한 영역. 실패가 없으면 빈 목록
 * @param dataSyncedAt            홈에 쓴 잔액을 금융망에서 읽은 UTC 시각. 잔액을 쓰지 않았으면 null
 * @param todos                   할 일(최대 {@value HomeService#MAX_TODOS}개). 없으면 빈 목록
 * @param recentPayment           가장 최근 승인 결제. 없으면 null
 * @param onboarding              연결 단계
 * @param todayCard               오늘 쓸 카드. 고를 근거가 없으면 null
 */
public record HomeResponse(Cards cards, List<PerformanceAlert> performanceAlerts, BalanceForecast balanceForecast,
        MonthSummary monthSummary, int unreadNotificationCount, List<PartialError> partialErrors,
        LocalDateTime dataSyncedAt, List<Todo> todos, RecentPayment recentPayment, Onboarding onboarding,
        TodayCard todayCard) {

    public record Cards(int count, List<CardItem> items) {}

    /**
     * @param issuerColor  카드사 색(#RRGGBB). 모르는 카드사면 null
     * @param maskedNumber 앞 4자리·끝 4자리만 보인다
     * @param cardType     CREDIT·CHECK
     */
    public record CardItem(Long userCardId, String nickname, String issuerColor, String maskedNumber,
            String cardType) {}

    public record PerformanceAlert(Long userCardId, String nickname, long remainingAmount, Double achievementRate,
            Integer daysLeft) {}

    /**
     * 기준 계좌({@code linked_account.is_primary}) 하나의 잔고예보 요약. 기간은 잔고예보 설정을 따른다.
     *
     * @param currentBalance      현재 잔액(원)
     * @param endBalancePredicted 기간 마지막 날 잔액(가장 나쁜 선)
     * @param nextRiskDate        잔액이 안전 잔액 밑으로 처음 내려가는 날(한국 날짜). 없으면 null
     * @param horizonDays         예보 일수
     * @param minBalance          기간 중 가장 나쁜 선의 최저 잔액
     * @param nextIncome          다음 월급. 기간이 '다음 월급일 전까지'일 때만 있다
     * @param isEstimate          추정이 섞였는지(잔고예보와 같은 값)
     * @param isProvisional       생활비 자료가 없어 보수적 선 없이 계산했는지
     */
    public record BalanceForecast(Long accountId, Long currentBalance, Long endBalancePredicted,
            LocalDate nextRiskDate, Integer horizonDays, Long minBalance, NextIncome nextIncome, Boolean isEstimate,
            Boolean isProvisional) {}

    /** @param amount 월급 금액(원). 금액 미정 항목이면 null */
    public record NextIncome(LocalDate date, Long amount, String label) {}

    public record MonthSummary(String yearMonth, Long totalSpent, Long totalBenefit) {}

    /**
     * 영역 하나의 실패.
     *
     * @param area 응답 칸 이름(cards·balanceForecast·unreadNotificationCount·todos·recentPayment·todayCard·onboarding)
     * @param code 에러 코드(예: FIN_API_UNAVAILABLE). 예상하지 못한 오류는 INTERNAL_ERROR
     */
    public record PartialError(String area, String code) {}

    /** @param tone PRIMARY·SUCCESS·WARNING·ERROR·NEUTRAL */
    public record Todo(String title, String tag, String tone, Long userCardId) {}

    /**
     * @param amount        승인 금액(원)
     * @param cardName      별칭, 없으면 상품명
     * @param benefitAmount 이 결제의 혜택 합계(원). 혜택 줄이 없으면 null
     * @param paidAt        승인 UTC 시각
     */
    public record RecentPayment(Long paymentId, String merchantName, long amount, String cardName,
            Long benefitAmount, LocalDateTime paidAt) {}

    /** @param step LINK(연결 계좌 없음)·DONE. 앱의 CASHFLOW 단계는 기준이 없어 아직 쓰지 않는다 */
    public record Onboarding(String step) {}

    /** @param benefit 고른 까닭 한 줄(예: "카페 결제에 혜택이 가장 커요") */
    public record TodayCard(Long userCardId, String cardName, String benefit) {}
}
