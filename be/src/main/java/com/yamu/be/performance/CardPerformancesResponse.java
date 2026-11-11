package com.yamu.be.performance;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.yamu.be.card.CardIssuers;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 카드별 실적 현황(API 명세 V2 #65). 응답 모양은 앱 {@code PerformanceModels.kt}의 {@code CardPerformancesDto}에 맞춘다
 * (S15P31E103-373).
 *
 * @param yearMonth    한국 기준 실적 월(YYYY-MM)
 * @param daysLeft     이번 달이면 말일까지 남은 날(오늘 제외), 지난 달이면 0, 다음 달 이후면 그달 일수
 * @param cards        등록된 카드(ACTIVE·INACTIVE). 순서는 카드 목록과 같다
 * @param dataSyncedAt 외부 동기화 시각. 실적은 결제 승인 때 바로 쌓여 동기화 개념이 없으므로 null
 */
public record CardPerformancesResponse(String yearMonth, int daysLeft, List<Item> cards, LocalDateTime dataSyncedAt) {

    /**
     * 카드 한 장.
     *
     * @param nickname         카드 별칭. 없으면 상품명(카드 목록과 같음)
     * @param issuerName       카드사 이름({@code issuer.name}과 같음)
     * @param targetAmount     목표 실적. 카드 목록의 목표와 같은 규칙(BE1 -308 임시 규칙). 실적 조건이 없으면 null
     * @param targetLabel      목표 표시. 예: "30만원 이상". 목표가 없으면 null
     * @param recognizedAmount 그달 인정 실적. 실적 행이 없으면 0
     * @param remainingAmount  목표 − 실적, 0 아래로 내려가지 않음. 목표가 없으면 0
     * @param status           {@link PerformanceStatus}
     * @param guide            유불리 판정. 375(유불리 분석) 범위라 아직 null
     * @param decision         사용자 판단 기록. 375 범위라 아직 null
     * @param isProvisional    그달 실적 인정액이 미확인인 승인 결제가 있으면 true(실적이 나중에 늘 수 있음)
     * @param recentChange     그달 실적에 가장 최근 반영된 승인 결제. 없으면 null
     */
    public record Item(long userCardId, String nickname, CardIssuers.Issuer issuer, String issuerName, String cardType,
            Long targetAmount, String targetLabel, long recognizedAmount, long remainingAmount, PerformanceStatus status,
            String guide, Object decision, @JsonProperty("isProvisional") boolean isProvisional,
            RecentChange recentChange) {
    }

    /**
     * @param amount       그 결제의 실적 인정액(원, 0보다 큼)
     * @param approvedAt   승인 시각(UTC)
     * @param merchantName 결제 당시 가맹점 이름
     */
    public record RecentChange(long amount, LocalDateTime approvedAt, String merchantName) {
    }
}
