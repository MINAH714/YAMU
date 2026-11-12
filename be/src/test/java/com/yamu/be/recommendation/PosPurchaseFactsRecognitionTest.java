package com.yamu.be.recommendation;

import static com.yamu.be.recommendation.RecognitionFixtures.product;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yamu.be.payment.recommend.PosPurchaseFacts;
import com.yamu.be.recommendation.RecommendationInput.Plan;
import com.yamu.be.recommendation.RecommendationInput.Transaction;
import java.io.IOException;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * 결제가 넘기는 POS 거래 사실({@link PosPurchaseFacts})과 기본 모의 구매 보완({@link BasicPurchaseSimulation})만으로
 * 실적 인정 계산이 "사실 부족 미확인"에 빠지지 않는지 실제 정책 파일 20종으로 본다(Task 5.2.11).
 *
 * <p>신규 발급 유예·상품 할인 발생 여부는 결제가 넘기지 않는 값이라 여기서 false로 두고 본다(각각 최소 실적·할인 계산 쪽 값이다).
 */
class PosPurchaseFactsRecognitionTest {

    private static final Instant NOV_6_KST_NOON = Instant.parse("2026-11-06T03:00:00Z");

    private final RecommendationRecognitionResolver resolver = new RecommendationRecognitionResolver(new ObjectMapper());

    @Test
    void 정책이_보는_제외_거래_종류는_모두_결제나_기본_모의_구매가_확인해_넘긴다() {
        Map<String, Boolean> facts = posFacts(null);
        List<String> missing = new ArrayList<>();
        for (var policy : policies()) {
            for (var key : policy.excludedFacts()) {
                if (!facts.containsKey(key)) {
                    missing.add(policy.cardId() + ":" + key);
                }
            }
        }
        // 실패하면 정책 파일에 새 거래 종류가 생긴 것이다. POS 일반 구매에서 "아니다"가 확정인지 정해 PosPurchaseFacts에 넣는다
        assertThat(missing).isEmpty();
        assertThat(facts).containsEntry("REGION:DOMESTIC", true)
                .containsEntry("TRANSACTION_KIND:UNAPPROVED_VOUCHER", false)
                .containsEntry("TRANSACTION_KIND:CANCELED_TRANSACTION", false)
                .containsEntry("PERFORMANCE:POSTPAID_TRANSIT", false)
                .containsEntry("PERFORMANCE:TELECOM_AUTOPAY", false);
    }

    @Test
    void POS_사실을_넘기면_규칙이_있는_상품은_인정월과_인정액이_나오고_최소_실적_0_상품만_비어_있다() {
        int withAmount = 0;
        int withMonth = 0;
        for (var policy : policies()) {
            var issues = new ArrayList<RecommendationDataIssue>();
            var recognition = resolver.resolve(1, product(policy.cardId(), 10), transaction(posFacts(policy.cardId())), null,
                    issues);
            if (Long.valueOf(0L).equals(policy.minimum())) {
                // 실적 조건이 없는 상품: BE3가 인정월·인정액을 만들지 않는다(결제는 인정액 미확인·승인월로 저장)
                assertThat(recognition.period()).as(policy.cardId()).isNull();
                assertThat(recognition.recognizedAmount()).as(policy.cardId()).isNull();
                continue;
            }
            assertThat(recognition.recognizedAmount()).as(policy.cardId()).isEqualTo(10_000L);
            withAmount++;
            if (policy.monthRule() != null) {
                assertThat(recognition.period()).as(policy.cardId()).isEqualTo(YearMonth.of(2026, 11));
                withMonth++;
            } else {
                // 인정월 규칙이 없는 상품: 결제가 승인월로 채운다
                assertThat(recognition.period()).as(policy.cardId()).isNull();
            }
            assertThat(issues).as(policy.cardId()).extracting(RecommendationDataIssue::code)
                    .noneMatch(code -> code.startsWith("RECOGNITION_AMOUNT_UNCONFIRMED"));
        }
        assertThat(withAmount).isEqualTo(10);
        assertThat(withMonth).isEqualTo(8);
    }

    @Test
    void 사실을_넘기지_않으면_모든_상품이_미확인이다() {
        for (var policy : policies()) {
            var recognition = resolver.resolve(1, product(policy.cardId(), 10),
                    transaction(Map.of()), null,
                    new ArrayList<>());
            assertThat(recognition.recognizedAmount()).as(policy.cardId()).isNull();
        }
    }

    /** 결제가 넘기는 사실 + 기본 모의 구매 보완. cardId가 있으면 결제 밖의 값(신규 유예·할인 발생)을 false로 고정한다. */
    private static Map<String, Boolean> posFacts(String cardId) {
        var facts = new HashMap<>(BasicPurchaseSimulation.apply(base(PosPurchaseFacts.verified()), "OFFLINE", "GENERAL").facts());
        if (cardId != null) {
            facts.put("PERFORMANCE:INTRODUCTORY_WAIVER_ACTIVE:" + cardId, false);
            facts.put("PERFORMANCE:PRODUCT_DISCOUNT_APPLIED:" + cardId, false);
        }
        return facts;
    }

    private static Transaction base(Map<String, Boolean> facts) {
        return new Transaction(10_000, Plan.LUMP_SUM, 1, NOV_6_KST_NOON, 1L, "COFFEE_SHOP", facts);
    }

    private static Transaction transaction(Map<String, Boolean> facts) {
        return base(facts);
    }

    private static List<RecommendationRecognitionResolver.Policy> policies() {
        try (var input = new ClassPathResource("recommendation/performance-policies.json").getInputStream()) {
            return new ObjectMapper().readValue(input, RecommendationRecognitionResolver.Catalog.class).policies();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
