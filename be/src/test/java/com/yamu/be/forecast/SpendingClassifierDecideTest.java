package com.yamu.be.forecast;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 분류 계산(DB 없음). 동시 실행 때 생기는 "대상이면서 이미 LIVING인 결제"를 직접 만들어 본다. */
class SpendingClassifierDecideTest {
    private static final LocalDateTime BASE = LocalDateTime.of(2026, 6, 10, 3, 0);

    @Test
    void 다른_요청이_먼저_LIVING으로_쓴_대상은_표본에_한_번만_센다() {
        // 앞서 분류된 LIVING 3건
        List<SpendingClassifier.Living> living = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            living.add(new SpendingClassifier.Living(i, 10_000L, BASE.minusDays(10 + i)));
        }
        // 결제 10은 이 요청이 미분류로 읽은 뒤 다른 요청이 LIVING으로 써서 표본 쿼리에도 나왔다
        SpendingClassifier.Unclassified raced = new SpendingClassifier.Unclassified(10, 10_000L, 1, BASE);
        living.add(new SpendingClassifier.Living(10, 10_000L, BASE));
        SpendingClassifier.Unclassified target = new SpendingClassifier.Unclassified(11, 100_000L, 1,
                BASE.plusHours(1));

        Map<Long, String> decisions = SpendingClassifier.decide(List.of(raced, target), living);

        // 결제 10을 한 번만 세면 표본은 4건이라 중앙값 기준을 쓰지 않는다(두 번 세면 5건이 되어 LARGE로 틀림)
        assertThat(decisions).containsExactly(Map.entry(10L, "LIVING"), Map.entry(11L, "LIVING"));
    }

    @Test
    void 표본_쿼리와_상관없이_같은_대상이면_결과가_같다() {
        List<SpendingClassifier.Living> before = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            before.add(new SpendingClassifier.Living(i, 10_000L, BASE.minusDays(10 + i)));
        }
        List<SpendingClassifier.Unclassified> targets = List.of(
                new SpendingClassifier.Unclassified(10, 10_000L, 1, BASE),
                new SpendingClassifier.Unclassified(11, 100_000L, 1, BASE.plusHours(1)));
        List<SpendingClassifier.Living> raced = new ArrayList<>(before);
        raced.add(new SpendingClassifier.Living(10, 10_000L, BASE));

        assertThat(SpendingClassifier.decide(targets, raced)).isEqualTo(SpendingClassifier.decide(targets, before))
                .containsEntry(11L, "LARGE");
    }
}
