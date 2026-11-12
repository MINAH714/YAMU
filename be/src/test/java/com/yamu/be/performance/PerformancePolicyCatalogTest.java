package com.yamu.be.performance;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yamu.be.merchant.MerchantCategories;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * 실제 BE3 원본 {@code recommendation/performance-policies.json}을 읽는다(S15P31E103-375). 카탈로그는 모르는 칸을 무시하고
 * 읽어서, BE3가 칸 이름이나 업종 코드 체계를 바꾸면 오류 없이 실적 제외 업종이 비어 버린다. 그때 여기서 실패하게 한다.
 */
class PerformancePolicyCatalogTest {

    static final ObjectMapper JSON = new ObjectMapper();
    final PerformancePolicyCatalog catalog = new PerformancePolicyCatalog(JSON);

    @Test
    void 원본의_실적_제외_업종과_불확실_업종을_그대로_읽는다() {
        PerformancePolicyCatalog.Policy play = catalog.find("LOTTE", "LOCA LIKIT Play", "CREDIT").orElseThrow();

        assertThat(play.excludes("SUBWAY")).isTrue();
        assertThat(play.excludes("WATER_UTILITY")).isFalse();
        assertThat(play.uncertain("WATER_UTILITY")).isTrue();
        assertThat(catalog.find("SHINHAN", "신한카드 Deep Oil", "CREDIT").orElseThrow().excludes("GAS_STATION")).isTrue();
    }

    @Test
    void 원본_칸_이름이_바뀌면_실패한다() throws IOException {
        List<JsonNode> raw = rawPolicies();
        // 지금 원본은 모든 정책에 두 칸이 있다(빈 목록 포함). 하나라도 빠지면 이름이 바뀐 것으로 본다
        assertThat(raw).as("excludedCategories 칸").allMatch(p -> p.path("excludedCategories").isArray());
        assertThat(raw).as("uncertainCategories 칸").allMatch(p -> p.path("uncertainCategories").isArray());
        assertThat(raw).as("실적 제외 업종이 있는 정책")
                .anyMatch(p -> p.path("excludedCategories").size() > 0);
        assertThat(raw).as("불확실 업종이 있는 정책")
                .anyMatch(p -> p.path("uncertainCategories").size() > 0);

        for (JsonNode node : raw) {
            PerformancePolicyCatalog.Policy policy = catalog.find(node.path("issuer").asText(null),
                    node.path("productName").asText(), node.path("cardType").asText()).orElseThrow();
            assertThat(policy.excludedCategories()).isEqualTo(texts(node.path("excludedCategories")));
            assertThat(policy.uncertainCategories()).isEqualTo(texts(node.path("uncertainCategories")));
        }
    }

    @Test
    void 업종은_예정_지출과_같은_가맹점_업종_코드다() throws IOException {
        Set<String> known = MerchantCategories.list().stream()
                .map(MerchantCategories.Category::merchantCategoryCode).collect(Collectors.toSet());
        List<String> used = new ArrayList<>();
        rawPolicies().forEach(p -> {
            used.addAll(texts(p.path("excludedCategories")));
            used.addAll(texts(p.path("uncertainCategories")));
        });

        assertThat(used).isNotEmpty();
        assertThat(known).containsAll(used);
    }

    private static List<JsonNode> rawPolicies() throws IOException {
        try (var input = new ClassPathResource("recommendation/performance-policies.json").getInputStream()) {
            List<JsonNode> nodes = new ArrayList<>();
            JSON.readTree(input).path("policies").forEach(nodes::add);
            assertThat(nodes).as("policies 목록").isNotEmpty();
            return nodes;
        }
    }

    private static List<String> texts(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(v -> values.add(v.asText()));
        return values;
    }
}
