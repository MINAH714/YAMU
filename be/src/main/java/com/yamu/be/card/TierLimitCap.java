package com.yamu.be.card;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 실적 구간 하나에서 한 달에 받을 수 있는 원화 혜택의 최대치(S15P31E103-373). {@link CardBenefitSummaryReader}가 구간 정보를
 * 만들 때 쓴다. DB를 읽지 않는 계산이다.
 *
 * <p>혜택 하나가 여러 원화·월 한도에 걸릴 수 있다(예: 통합 한도 2만원 안의 영화 한도 8천원). 그룹 한도를 그냥 더하면 겹친
 * 만큼 커지므로, 그룹이 포함 관계(안쪽 그룹의 혜택이 바깥 그룹에 모두 들어 있음)일 때 안쪽 한도의 합과 바깥 한도 중 작은 값을
 * 쓴다. 다음은 최대치를 말할 수 없어 null이다(추천 엔진처럼 모르는 값을 지어내지 않는다):
 * <ul>
 *   <li>원화·월 한도가 하나도 없는 혜택이 있다(그 혜택은 원화로 상한이 없다)</li>
 *   <li>한도 값을 모르는 그룹이 있다(값 null)</li>
 *   <li>두 그룹의 혜택이 일부만 겹친다(포함도 분리도 아님)</li>
 * </ul>
 */
final class TierLimitCap {

    private TierLimitCap() {
    }

    /**
     * @param groupsOfBenefit 그 구간에서 쓸 수 있는 혜택 → 그 혜택이 걸린 원화·월 한도 그룹. 걸린 그룹이 없으면 빈 집합
     * @param groupValue      그룹 → 그 구간의 한도 값(원). 모르면 null 값
     * @return 한 달 최대 원화 혜택. 쓸 수 있는 혜택이 없거나 최대치를 말할 수 없으면 null
     */
    static Long monthlyMax(Map<Long, Set<Long>> groupsOfBenefit, Map<Long, Long> groupValue) {
        if (groupsOfBenefit.isEmpty()) {
            return null;
        }
        Map<Long, Set<Long>> members = new HashMap<>();
        for (Map.Entry<Long, Set<Long>> entry : groupsOfBenefit.entrySet()) {
            if (entry.getValue().isEmpty()) {
                return null;
            }
            for (Long group : entry.getValue()) {
                if (!groupValue.containsKey(group) || groupValue.get(group) == null) {
                    return null;
                }
                members.computeIfAbsent(group, ignored -> new HashSet<>()).add(entry.getKey());
            }
        }
        // 작은 그룹부터. 혜택 집합이 같으면 번호 순으로 앞의 것을 안쪽으로 본다
        List<Long> order = new ArrayList<>(members.keySet());
        order.sort(Comparator.<Long>comparingInt(g -> members.get(g).size()).thenComparing(g -> g));
        Map<Long, Long> parent = new HashMap<>();
        for (int i = 0; i < order.size(); i++) {
            Set<Long> inner = members.get(order.get(i));
            for (int j = i + 1; j < order.size(); j++) {
                Set<Long> outer = members.get(order.get(j));
                boolean disjoint = inner.stream().noneMatch(outer::contains);
                if (outer.containsAll(inner)) {
                    parent.putIfAbsent(order.get(i), order.get(j));
                } else if (!disjoint) {
                    return null;
                }
            }
        }
        Map<Long, Long> cap = new HashMap<>();
        for (Long group : order) {
            List<Long> children = order.stream().filter(c -> group.equals(parent.get(c))).toList();
            Set<Long> covered = new HashSet<>();
            children.forEach(c -> covered.addAll(members.get(c)));
            long value = groupValue.get(group);
            if (covered.containsAll(members.get(group))) {
                value = Math.min(value, children.stream().mapToLong(cap::get).sum());
            }
            cap.put(group, value);
        }
        return order.stream().filter(g -> !parent.containsKey(g)).mapToLong(cap::get).sum();
    }
}
