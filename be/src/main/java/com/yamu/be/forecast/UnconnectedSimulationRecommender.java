package com.yamu.be.forecast;

import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 추천 엔진을 연결하기 전의 임시 구현. 늘 "계산할 수 없음"이다(2026-11-05 결정).
 * 그래서 카드를 고르지 않은 시뮬레이션은 422 DATA_INSUFFICIENT, 카드를 고른 시뮬레이션은 혜택·대안·실적 영향 없이(null) 잔고만 비교한다.
 * 가짜 추천·0원 혜택을 만들지 않는다. BE 2·BE 3와 호출 방식이 정해지면 실제 구현으로 바꾸고 이 클래스는 지운다.
 */
@Component
class UnconnectedSimulationRecommender implements SimulationRecommender {

    @Override
    public Optional<Recommendation> recommend(Request request) {
        return Optional.empty();
    }
}
