package com.yamu.be.forecast;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 임시 생활비 원천. 결제의 소비 분류({@code payment_transaction.spending_class})를 채우는 코드가 아직 없어 생활비로 확인된
 * 소비가 하나도 없으므로, 어떤 날도 "확인했다"고 하지 않고 줄을 주지 않는다. 잔고예보는 이것을 자료 없음으로 보고
 * 보수적 선만 비운다. LIVING 결제를 조회해 0원·complete=true로 주면 실제로 쓴 돈을 0원으로 확정하게 되어 그렇게 하지 않는다.
 *
 * <p>소비 분류와 실제 원천은 BE 4가 구현한다(2026-11-04 BE 2 합의, 리뷰 BE 2). 그때 이 클래스를 지운다.
 */
@Component
class UnclassifiedLivingCostReader implements LivingCostReader {

    private final Clock clock;

    UnclassifiedLivingCostReader(Clock clock) {
        this.clock = clock;
    }

    @Override
    public Spending dailySpending(Long userId, List<Long> accountIds, LocalDate fromDate, LocalDate toDate) {
        return new Spending(List.of(), LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
    }
}
