package com.yamu.be.payment.recommend;

/**
 * 결제 조건에 맞는 최적 카드를 고르는 추천 엔진으로 가는 문(포트). HTTP API가 아니라 결제 요청·승인 안에서 부른다.
 * (API 명세 V2 「[내부 설명·HTTP API 아님] 카드 추천 공통 처리」 2026-10-31판, FR-011~FR-018)
 *
 * <p>임시 계약(S15P31E103-197): BE3의 추천 연계 설계(S15P31E103-171)가 아직 없어, 명세의 최소 연결 규격(2.2)과 내부 결과 형식을 따라 BE2가 먼저 정했다.
 * BE3 구현이 들어오면 이 인터페이스의 빈으로 등록하고 {@link MockCardRecommender}는 빠진다({@link PaymentRecommendConfig}).
 * 입출력이 달라지면 여기부터 고친다.
 *
 * <p>추천은 읽기만 한다. 승인·청구·실적·혜택 사용량·신용한도를 바꾸지 않고, 같은 입력·카드 상태·규칙 버전이면 같은 결과를 낸다(FR-015).
 */
public interface CardRecommender {

    RecommendationResult recommend(RecommendationRequest request);
}
