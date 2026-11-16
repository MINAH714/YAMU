package com.yamu.be.payment.recommend;

import java.util.function.LongFunction;

/**
 * 결제 조건에 맞는 최적 카드를 고르는 추천 엔진으로 가는 문(포트). HTTP API가 아니라 결제 요청·승인 안에서 부른다.
 * (API 명세 V2 「[내부 설명·HTTP API 아님] 카드 추천 공통 처리」 2026-10-31판, FR-011~FR-018)
 *
 * <p>2026-11-03 승인 계약: {@link DatabaseCardRecommender}가 실제 추천 서비스를 호출하며 조건부 Mock 빈은 빠진다.
 * 결제 담당은 최초 보존한 거래 조건을 전달하고 결제 가능 상태는 추천 서비스가 조회한다.
 * 추천 결과 보존은 {@link DatabaseRecommendationResultStore}를 호출자의 결제 트랜잭션에서 호출한다. 이 포트 자체는 저장하지 않는다.
 *
 * <p>추천은 읽기만 한다. 승인·청구·실적·혜택 사용량·신용한도를 바꾸지 않고, 같은 입력·카드 상태·규칙 버전이면 같은 결과를 낸다(FR-015).
 */
public interface CardRecommender {

    RecommendationResult recommend(RecommendationRequest request);

    /** 저장 전 조회를 완료한다. 반환 함수에는 이 요청에서 발급된 결제 ID만 전달한다. */
    default LongFunction<RecommendationResult> prepare(RecommendationRequest request) {
        return paymentId -> recommend(new RecommendationRequest(paymentId, request.userId(), request.terms(),
                request.merchant(), request.requestedAt(), request.purchaseItemCode(), request.availability(),
                request.verifiedFacts(), request.basket()));
    }
}
