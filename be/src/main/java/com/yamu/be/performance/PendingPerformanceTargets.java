package com.yamu.be.performance;

import java.time.LocalDate;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 임시: 카드별 실적 현황(S15P31E103-373)이 develop에 들어오기 전까지 목표 실적을 모른다고 답한다. 그래서 유불리 분석은
 * 422 DATA_INSUFFICIENT를 돌려준다. 373 머지 뒤 develop을 합쳐 BE1 목표 규칙을 쓰는 구현으로 바꾸고 이 클래스는 지운다.
 */
@Component
class PendingPerformanceTargets implements PerformanceTargets {

    @Override
    public Optional<PerformanceAnalyzer.Target> targetFor(long cardProductId, long recognized, LocalDate today) {
        return Optional.empty();
    }
}
