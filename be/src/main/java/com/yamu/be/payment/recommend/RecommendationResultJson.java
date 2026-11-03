package com.yamu.be.payment.recommend;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 응답 DTO와 같은 형식으로 보존하며 구형 거래에 결과를 만들어 넣지 않는다. */
@Component
@RequiredArgsConstructor
public class RecommendationResultJson {
    private final ObjectMapper json;

    public String encode(RecommendationResult result) {
        java.util.Objects.requireNonNull(result);
        try { return json.writeValueAsString(result); }
        catch (JsonProcessingException ex) { throw new IllegalArgumentException("추천 결과 직렬화 실패"); }
    }

    public RecommendationResult decode(String value) {
        try {
            var tree = json.readTree(value);
            if (tree == null || !tree.path("schemaVersion").isInt() || tree.path("schemaVersion").intValue() != 1
                    || !tree.path("candidates").isArray()) throw new IllegalArgumentException("추천 결과 버전/후보 누락");
            for (var candidate : tree.path("candidates")) {
                if (!candidate.path("unallocatedBenefitGroups").isArray() || !candidate.path("netBenefit").isNumber()) {
                    throw new IllegalArgumentException("추천 총액/배분 배열 누락");
                }
            }
            // 트리는 형식 검증에만 쓴다. 실수 노드를 거치면 원시 정률 금액의 소수 정밀도가 손실될 수 있다.
            return json.readValue(value, RecommendationResult.class);
        } catch (JsonProcessingException ex) { throw new IllegalArgumentException("추천 결과 JSON 형식 오류"); }
    }
}
