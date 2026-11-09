package com.yamu.be.card;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 구간 하나의 한 달 최대 원화 혜택(S15P31E103-373). DB 없음. 혜택·그룹 번호는 임의. */
class TierLimitCapTest {

    @Test
    void 서로_다른_한도는_더한다() {
        // 신한 Mr.Life 30만 구간: 공과금 3천 · 시간대 1만 · 주말 3천
        assertThat(TierLimitCap.monthlyMax(
                Map.of(1L, Set.of(10L), 2L, Set.of(20L), 3L, Set.of(30L)),
                Map.of(10L, 3_000L, 20L, 10_000L, 30L, 3_000L))).isEqualTo(16_000L);
    }

    @Test
    void 같은_한도를_여러_혜택이_나눠_쓰면_한_번만_센다() {
        assertThat(TierLimitCap.monthlyMax(Map.of(1L, Set.of(10L), 2L, Set.of(10L)), Map.of(10L, 13_000L)))
                .isEqualTo(13_000L);
    }

    @Test
    void 통합_한도_안의_혜택별_한도는_통합_한도를_넘지_않는다() {
        // KB 노리2 20만 구간: 통합 2만원 안에 모바일 5천 · 영화 8천 · 놀이공원 1만5천 · 커피 3천
        Map<Long, Set<Long>> benefits = Map.of(1L, Set.of(99L, 11L), 2L, Set.of(99L, 12L), 3L, Set.of(99L, 13L),
                4L, Set.of(99L, 14L));
        Map<Long, Long> values = Map.of(99L, 20_000L, 11L, 5_000L, 12L, 8_000L, 13L, 15_000L, 14L, 3_000L);
        assertThat(TierLimitCap.monthlyMax(benefits, values)).isEqualTo(20_000L);
    }

    @Test
    void 안쪽_한도의_합이_더_작으면_그_합이다() {
        Map<Long, Long> values = Map.of(99L, 20_000L, 11L, 5_000L, 12L, 3_000L);
        assertThat(TierLimitCap.monthlyMax(Map.of(1L, Set.of(99L, 11L), 2L, Set.of(99L, 12L)), values))
                .isEqualTo(8_000L);
    }

    @Test
    void 통합_한도_안에_혜택별_한도가_없는_혜택이_있으면_통합_한도다() {
        Map<Long, Long> values = Map.of(99L, 20_000L, 11L, 5_000L);
        assertThat(TierLimitCap.monthlyMax(Map.of(1L, Set.of(99L, 11L), 2L, Set.of(99L)), values))
                .isEqualTo(20_000L);
    }

    @Test
    void 혜택_집합이_같은_두_한도는_작은_값이다() {
        assertThat(TierLimitCap.monthlyMax(Map.of(1L, Set.of(10L, 20L)), Map.of(10L, 7_000L, 20L, 5_000L)))
                .isEqualTo(5_000L);
    }

    @Test
    void 원화_월_한도가_없는_혜택이_있으면_null이다() {
        assertThat(TierLimitCap.monthlyMax(Map.of(1L, Set.of(10L), 2L, Set.of()), Map.of(10L, 3_000L))).isNull();
    }

    @Test
    void 한도_값을_모르면_null이다() {
        Map<Long, Long> values = new HashMap<>();
        values.put(10L, null);
        assertThat(TierLimitCap.monthlyMax(Map.of(1L, Set.of(10L)), values)).isNull();
    }

    @Test
    void 일부만_겹치는_한도는_null이다() {
        // 그룹 10 = {1,2}, 그룹 20 = {2,3}: 포함도 분리도 아니다
        assertThat(TierLimitCap.monthlyMax(Map.of(1L, Set.of(10L), 2L, Set.of(10L, 20L), 3L, Set.of(20L)),
                Map.of(10L, 5_000L, 20L, 5_000L))).isNull();
    }

    @Test
    void 쓸_수_있는_혜택이_없으면_null이다() {
        assertThat(TierLimitCap.monthlyMax(Map.of(), Map.of())).isNull();
    }
}
