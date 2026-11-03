package com.yamu.be.payment.approve;

import lombok.extern.slf4j.Slf4j;

/**
 * 임시 한도 포트(POL-013 후속 설계 전): 한도를 보지 않고 검사를 생략한다. 모든 신용 승인이 한도 때문에 거절되지 않는다.
 * 초기 한도·저장 단위를 임의로 정하지 않기 위해 설정값 한도도 두지 않는다(2026-11-02 결정).
 */
@Slf4j
public class UnlimitedCreditLimitSource implements CreditLimitSource {

    @Override
    public LimitCheck check(Long userId, Long userCardId, long principal) {
        log.debug("모의 신용한도 검사 생략(임시) userCardId={} principal={}", userCardId, principal);
        return LimitCheck.skipped();
    }
}
