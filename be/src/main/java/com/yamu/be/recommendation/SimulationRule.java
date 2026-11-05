package com.yamu.be.recommendation;

import java.util.List;

/** 실제 약관과 구분하는, 이번 계산에 실제 사용한 승인된 모의 보완 근거. */
public record SimulationRule(String id, int version, String description, Long userCardId, Long benefitId,
                             List<String> appliedFields) {
    public SimulationRule { appliedFields = List.copyOf(appliedFields); }
}
