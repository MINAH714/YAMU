package com.yamu.be.payment.approve;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 임시 승인 실행(Task 5.2.3·5.2.4 구현 전). 승인 흐름(멱등·상태 전이·동시 승인 방지·재승인)을 개발·시험할 수 있게 모든 카드를 승인한다.
 *
 * <p>하는 것: 승인액·출금액 = POS 요청 금액(혜택 0원), 실적 인정액 0(실적 계산 전), 실적 월 = 승인 시각의 한국 월.
 * 하지 않는 것: 금융망 출금, 잔액·한도·미납 확인, 혜택 재계산, 청구 회차 생성. 그래서 거절(DECLINED)을 내지 않는다.
 */
public class TemporaryPaymentApprover implements PaymentApprover {

    static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Override
    public ApprovalOutcome approve(ApprovalCommand command) {
        long amount = command.terms().amount();
        LocalDate performanceMonth = command.now().atOffset(ZoneOffset.UTC).atZoneSameInstant(KST)
                .toLocalDate().withDayOfMonth(1);
        return new ApprovalOutcome.Approved(amount, amount, 0, performanceMonth, List.of(), null);
    }
}
