package com.yamu.be.performance;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * 카드 상품별 실적 제외 업종(S15P31E103-375). 추천 엔진(BE3)이 실적 인정을 계산할 때 쓰는 원본
 * {@code recommendation/performance-policies.json}을 같이 읽는다. BE3 코드는 고치지 않으려고 같은 파일을 따로 읽는다
 * (BE3 {@code RecommendationRecognitionResolver}와 상품 고르는 기준이 같다: 카드사 코드·상품명·카드 종류).
 *
 * <p>옮길 수 있는 예정 지출을 고를 때만 쓴다. 업종 코드 하나로 판정하므로 거래 종류(세금·상품권 등) 제외는 보지 않는다.
 */
@Component
class PerformancePolicyCatalog {

    private final List<Policy> policies;

    PerformancePolicyCatalog(ObjectMapper json) {
        ObjectMapper lenient = json.copy().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        try (var input = new ClassPathResource("recommendation/performance-policies.json").getInputStream()) {
            policies = List.copyOf(lenient.readValue(input, Catalog.class).policies());
        } catch (IOException e) {
            throw new IllegalStateException("실적 정책 원본 매핑을 읽지 못했습니다.", e);
        }
    }

    /** 원본에 없는 상품이면 비어 있다(실적 제외 업종을 모름). */
    Optional<Policy> find(String issuerCode, String productName, String cardType) {
        return policies.stream()
                .filter(p -> p.issuer() != null && p.issuer().equals(issuerCode)
                        && p.productName().equals(productName) && p.cardType().equals(cardType))
                .findFirst();
    }

    record Catalog(List<Policy> policies) {
    }

    record Policy(String issuer, String productName, String cardType, List<String> excludedCategories,
            List<String> uncertainCategories) {

        Policy {
            excludedCategories = excludedCategories == null ? List.of() : List.copyOf(excludedCategories);
            uncertainCategories = uncertainCategories == null ? List.of() : List.copyOf(uncertainCategories);
        }

        boolean excludes(String category) {
            return category != null && Set.copyOf(excludedCategories).contains(category);
        }

        boolean uncertain(String category) {
            return category != null && Set.copyOf(uncertainCategories).contains(category);
        }
    }
}
