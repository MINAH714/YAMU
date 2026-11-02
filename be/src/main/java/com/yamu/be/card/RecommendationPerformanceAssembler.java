package com.yamu.be.card;

import com.yamu.be.recommendation.RecommendationInput.PerformanceProgress;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 동률용 실적 입력을 확인된 거래 인정월의 누적액과 조합한다. 카드 패키지의 기존 월 지정 조회를 재사용한다.
 * 카드 목록의 현재 월 조회와 무관하며, 이 클래스는 현재 월이나 상품별 실적 정책을 결정하지 않는다.
 *
 * <p>TODO(S15P31E103-221): 운영 호출부·상품별 인정 정책 공급은 아직 없다. 향후 입력 생성 담당은
 * 소유권을 확인한 카드와 확인된 인정월·인정액·그 기간의 최소 기준을 전달하고 반환값을 추천 입력에 연결해야 한다.
 * 이 컴포넌트만으로 추천 전체의 운영 조회 연결을 완료한 것은 아니다.
 */
@Component
@RequiredArgsConstructor
class RecommendationPerformanceAssembler {
    private final CardActivityReader activityReader;

    /**
     * 소유권 확인을 마친 카드별 거래 인정 정보. 같은 카드의 중복 입력은 거절한다.
     * 기간·인정액·최소 기준 미확인은 조회로 복원하거나 추정하지 않고 보조 정보 미확인으로 유지한다.
     */
    Map<Long, PerformanceProgress> assemble(Collection<Recognition> recognitions) {
        List<Recognition> inputs = List.copyOf(recognitions);
        if (inputs.stream().map(Recognition::userCardId).distinct().count() != inputs.size()) {
            throw new IllegalArgumentException("같은 카드의 실적 입력을 중복 전달할 수 없습니다.");
        }

        Map<YearMonth, List<Long>> cardsByPeriod = new TreeMap<>();
        for (Recognition recognition : inputs) {
            if (recognition.period() != null && recognition.minimumAmount() != null && recognition.recognizedAmount() != null) {
                cardsByPeriod.computeIfAbsent(recognition.period(), ignored -> new ArrayList<>())
                        .add(recognition.userCardId());
            }
        }
        Map<Long, Long> beforeAmounts = new LinkedHashMap<>();
        cardsByPeriod.forEach((period, ids) -> {
            Map<Long, Long> amounts = activityReader.recognizedAmounts(List.copyOf(ids), period);
            // 요청한 카드만 해당 인정월의 조회 결과에서 가져온다.
            ids.forEach(id -> {
                if (amounts.containsKey(id)) {
                    beforeAmounts.put(id, amounts.get(id));
                }
            });
        });

        Map<Long, PerformanceProgress> result = new LinkedHashMap<>();
        inputs.forEach(recognition -> result.put(recognition.userCardId(), new PerformanceProgress(recognition.period(),
                beforeAmounts.get(recognition.userCardId()), recognition.minimumAmount(), recognition.recognizedAmount())));
        return java.util.Collections.unmodifiableMap(result);
    }

    /** period는 상품 정책에서 확인한 이번 거래의 인정월이다. 서버 현재 월을 대신 넣지 않는다. */
    record Recognition(long userCardId, YearMonth period, Long minimumAmount, Long recognizedAmount) {
        Recognition {
            if (userCardId <= 0) {
                throw new IllegalArgumentException("카드 ID는 양수여야 합니다.");
            }
            // 엔진과 동일한 금액 검증. 누적액은 조회 후 PerformanceProgress 생성 시 검증한다.
            new PerformanceProgress(period, null, minimumAmount, recognizedAmount);
        }
    }
}
