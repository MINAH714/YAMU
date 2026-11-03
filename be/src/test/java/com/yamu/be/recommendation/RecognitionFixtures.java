package com.yamu.be.recommendation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yamu.be.card.domain.CardProduct;
import com.yamu.be.card.domain.CardType;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;

/** 실제 정책 파일을 사용하되, 거래 사실은 테스트가 명시적으로 확인한 가상 거래다. */
final class RecognitionFixtures {
    static RecommendationRecognitionResolver.Policy policy(String cardId) {
        try (var input = new ClassPathResource("recommendation/performance-policies.json").getInputStream()) {
            return new ObjectMapper().readValue(input, RecommendationRecognitionResolver.Catalog.class).policies()
                    .stream().filter(p -> p.cardId().equals(cardId)).findFirst().orElseThrow();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static CardProduct product(String cardId, long id) {
        var p = policy(cardId);
        return RecommendationRows.row(CardProduct.class, "id", id, "issuerCode", p.issuer(),
                "productName", p.productName(), "cardType", CardType.valueOf(p.cardType()));
    }

    static Map<String, Boolean> ordinaryDomestic(String cardId) {
        var result = new HashMap<String, Boolean>();
        policy(cardId).excludedFacts().forEach(key -> result.put(key, false));
        result.put("REGION:DOMESTIC", true);
        result.put("TRANSACTION_KIND:UNAPPROVED_VOUCHER", false);
        result.put("PERFORMANCE:POSTPAID_TRANSIT", false);
        result.put("PERFORMANCE:TELECOM_AUTOPAY", false);
        result.put("PERFORMANCE:INTRODUCTORY_WAIVER_ACTIVE:" + cardId, false);
        result.put("PERFORMANCE:PRODUCT_DISCOUNT_APPLIED:" + cardId, false);
        return result;
    }
}
