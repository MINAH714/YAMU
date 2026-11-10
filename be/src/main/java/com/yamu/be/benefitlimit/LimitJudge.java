package com.yamu.be.benefitlimit;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

/**
 * 월 혜택 한도 하나의 최대·남은 한도와 상태 판정(S15P31E103-374, 2026-11-10 결정). DB를 읽지 않는 계산만 있다.
 *
 * <p>최대 한도는 추천 엔진({@code RecommendationInputAssembler.maximum})과 같은 규칙이다. 실적 구간이 없으면 기본 한도,
 * 있으면 지난달 실적이 들어가는 구간의 한도다. 기본 한도와 구간이 같이 있거나, 둘 다 없거나, 구간이 겹치면 어느 값이 맞는지
 * 모르므로 판정하지 않는다({@link Optional#empty()}).
 *
 * <p>잠김: 지난달 실적으로는 이 한도에 연결된 혜택을 하나도 받을 수 없거나(혜택의 최소 전월 실적 미달), 맞는 구간이 없거나
 * 구간 한도가 0이면 LOCKED. 사유에 쓰는 기준은 실적이 그 값 이상이면 한도가 열리는 가장 작은 값이다. 실적과 상관없이 한도가
 * 0이면(열리는 기준이 없음) 잠김이 아니라 EXHAUSTED로 본다.
 */
final class LimitJudge {

    /** 남은 한도 ≤ 최대 한도 × 1/5(20%)이면 NEAR_END. */
    static final int NEAR_END_DIVISOR = 5;

    private LimitJudge() {
    }

    /**
     * @param baseLimitValue   한도 그룹의 기본 한도. 없으면 null
     * @param tiers            그 그룹의 실적 구간
     * @param benefitMinimums  그 그룹에 연결된 오늘 유효한 혜택들의 최소 전월 실적. 비어 있지 않음
     */
    record Rule(Long baseLimitValue, List<Tier> tiers, List<Long> benefitMinimums) {
    }

    /** @param max 실적 상한(미포함). 없으면 무제한 */
    record Tier(long min, Long max, long limit) {
    }

    /**
     * @param maximum         이번 달 최대 한도. 잠김이면 0
     * @param remaining       남은 한도(0 이상)
     * @param unlockThreshold 잠김일 때 한도가 열리는 지난달 실적 기준. 잠김이 아니면 null
     */
    record Judgement(LimitStatus status, long maximum, long remaining, Long unlockThreshold) {
    }

    /**
     * @param previous 지난달 인정 실적(행이 없으면 0)
     * @param used     이번 달 사용량(행이 없으면 0)
     * @return 최대 한도를 정할 수 없으면 empty
     */
    static Optional<Judgement> judge(Rule rule, long previous, long used) {
        if (!known(rule)) {
            return Optional.empty();
        }
        if (usable(rule, previous)) {
            long maximum = limitAt(rule, previous);
            long remaining = Math.max(0, maximum - used);
            LimitStatus status = remaining == 0 ? LimitStatus.EXHAUSTED
                    : remaining * NEAR_END_DIVISOR <= maximum ? LimitStatus.NEAR_END : LimitStatus.AVAILABLE;
            return Optional.of(new Judgement(status, maximum, remaining, null));
        }
        TreeSet<Long> candidates = new TreeSet<>(rule.benefitMinimums());
        rule.tiers().forEach(t -> candidates.add(t.min()));
        Long threshold = candidates.tailSet(previous, false).stream().filter(t -> usable(rule, t)).findFirst()
                .orElse(null);
        return Optional.of(threshold == null
                ? new Judgement(LimitStatus.EXHAUSTED, 0, 0, null)
                : new Judgement(LimitStatus.LOCKED, 0, 0, threshold));
    }

    /** 추천 엔진과 같다: 기본 한도·구간 중 하나만 있고, 구간끼리 겹치지 않아야 한다. */
    private static boolean known(Rule rule) {
        if (rule.tiers().isEmpty()) {
            return rule.baseLimitValue() != null;
        }
        if (rule.baseLimitValue() != null) {
            return false;
        }
        List<Tier> sorted = rule.tiers().stream().sorted(Comparator.comparingLong(Tier::min)).toList();
        for (int i = 1; i < sorted.size(); i++) {
            Long end = sorted.get(i - 1).max();
            if (end == null || end > sorted.get(i).min()) {
                return false;
            }
        }
        return true;
    }

    private static boolean usable(Rule rule, long performance) {
        return rule.benefitMinimums().stream().anyMatch(m -> m <= performance) && limitAt(rule, performance) > 0;
    }

    /** 실적이 {@code performance}일 때의 한도. 맞는 구간이 없으면 0. */
    private static long limitAt(Rule rule, long performance) {
        if (rule.tiers().isEmpty()) {
            return rule.baseLimitValue();
        }
        return rule.tiers().stream()
                .filter(t -> performance >= t.min() && (t.max() == null || performance < t.max()))
                .mapToLong(Tier::limit).findFirst().orElse(0);
    }
}
