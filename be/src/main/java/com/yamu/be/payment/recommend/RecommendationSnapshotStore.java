package com.yamu.be.payment.recommend;

import java.time.Duration;
import java.util.Optional;

/**
 * POS 요청 때 계산한 추천 결과를 결제가 진행되는 동안 보관한다. 앱의 바코드 조회·승인이 같은 스냅샷을 읽는다.
 *
 * <p>임시(명세 「결제 상태·예외 처리」 Q20): 정책 초안은 Redis, 추천 문서 10/31판은 DB JSON 보존이다.
 * 결정되면 구현({@link RedisRecommendationSnapshotStore})만 바꾸고 이 인터페이스는 그대로 둔다.
 */
public interface RecommendationSnapshotStore {

    void save(Long paymentId, RecommendationResult result, Duration retention);

    Optional<RecommendationResult> find(Long paymentId);

    void delete(Long paymentId);
}
