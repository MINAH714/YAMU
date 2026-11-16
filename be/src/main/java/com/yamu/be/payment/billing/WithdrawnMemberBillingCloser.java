package com.yamu.be.payment.billing;

import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.payment.approve.PaymentApprovalProperties;
import com.yamu.be.payment.approve.WithdrawalRecovery;
import java.time.Clock;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회원 탈퇴 때 끝나지 않은 청구서·환급을 닫는다(S15P31E103-443, 임시 정책). 회원 탈퇴(BE 1 {@code WithdrawalService})가 부른다.
 *
 * <p>YAMU는 카드사가 아니라 카드를 모아 보여주는 앱이므로, 탈퇴는 YAMU 연결만 끊는 것으로 본다. 청구가 남았다고 탈퇴를 막지 않고,
 * 남은 청구서({@link #OPEN_BILLS})와 환급({@link #OPEN_REFUNDS})을 돈을 움직이지 않고 {@code CLOSED_BY_WITHDRAWAL}로 닫는다.
 * 탈퇴하면 계좌번호를 덮어써 이 뒤로는 출금·입금을 할 수 없기 때문이기도 하다. 청구 확정·출금·환급 입금 작업은 상태로 대상을 고르므로
 * 닫힌 청구서·환급을 건너뛴다. 회차(bill_item)는 그대로 둔다.
 *
 * <pre>
 * 금융망에 출금·입금을 요청하고 유예(approve.recovery-grace) 안 → 409 BILL_OUTSTANDING·REFUND_PENDING (잠시 뒤 다시)
 * 요청했지만 유예가 지났는데 결과가 안 정해짐(운영 확인 대기 등) → 닫고 운영 확인 경고 로그
 * 나머지(진행 중·확정·미납 청구서, 대기·입금 실패 환급) → 닫음
 * </pre>
 *
 * <p>유예 안의 요청을 막는 이유: 출금·입금 작업은 금융망을 부르는 동안 잠금을 잡지 않는다. 그 사이 닫으면 돈은 나갔는데 기록은 닫혀
 * 어긋난다. 유예가 지나면 작업도 결과를 거래내역으로 확인하는 단계라 닫아도 새로 돈을 보내지는 않는다(작업은 결과를 저장하기 전에
 * 잠금 안에서 상태를 다시 보고 멈춘다). 그래도 그 요청이 실제로 처리됐다면 기록과 어긋나므로 경고 로그로 남긴다(한계).
 *
 * <p>잠금: card_bill·refund에는 user_id 인덱스가 없어 사용자 조건으로 잠그면 훑은 행을 모두 잠근다. 그래서 잠그지 않고 번호를 읽은 뒤
 * 그 번호로 번호 순 잠금(청구서 → 환급, 출금·취소와 같은 순서)을 하고 상태를 다시 본다. 읽은 뒤 잠그기 전에 새로 생긴 청구서는
 * 닫지 못한다(탈퇴 직전 승인과 겹칠 때, 기존 한계와 같음).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WithdrawnMemberBillingCloser {

    /** 탈퇴 때 닫는 청구서 상태. PAID·CANCELED는 이미 끝났다 */
    static final Set<BillStatus> OPEN_BILLS = EnumSet.of(BillStatus.ACCUMULATING, BillStatus.CONFIRMED, BillStatus.UNPAID);
    /** 탈퇴 때 닫는 환급 상태. COMPLETED·CANCELED는 이미 끝났다 */
    static final Set<RefundStatus> OPEN_REFUNDS = EnumSet.of(RefundStatus.PENDING, RefundStatus.FAILED);

    private final CardBillRepository bills;
    private final RefundRepository refunds;
    private final PaymentApprovalProperties approval;
    private final Clock clock;

    /**
     * 본인 인증 토큰을 쓰기 전의 확인. 잠그지 않고, 지금 금융망 결과를 기다리는 출금·입금이 있으면 409다.
     * 잠근 뒤 {@link #closeAll}이 한 번 더 본다.
     */
    @Transactional(readOnly = true)
    public void requireNothingInFlight(Long userId) {
        requireNothingInFlight(bills.findByUserIdAndStatusInOrderByIdAsc(userId, OPEN_BILLS),
                refunds.findByUserIdAndStatusInOrderByIdAsc(userId, OPEN_REFUNDS));
    }

    /**
     * 남은 청구서·환급을 잠그고 닫는다. 탈퇴 트랜잭션(사용자 행 잠금) 안에서 불러야 한다. 결과를 기다리는 출금·입금이 있으면 아무것도
     * 닫지 않고 409를 던진다(부르는 쪽 트랜잭션이 롤백된다).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Closed closeAll(Long userId) {
        List<Long> billIds = bills.findByUserIdAndStatusInOrderByIdAsc(userId, OPEN_BILLS).stream()
                .map(CardBill::getId).toList();
        List<CardBill> openBills = billIds.isEmpty() ? List.of() : bills.findAllForUpdate(billIds).stream()
                .filter(bill -> OPEN_BILLS.contains(bill.getStatus())).toList();
        List<Long> refundIds = refunds.findByUserIdAndStatusInOrderByIdAsc(userId, OPEN_REFUNDS).stream()
                .map(Refund::getId).toList();
        List<Refund> openRefunds = refundIds.isEmpty() ? List.of() : refunds.findAllForUpdate(refundIds).stream()
                .filter(refund -> OPEN_REFUNDS.contains(refund.getStatus())).toList();
        requireNothingInFlight(openBills, openRefunds);

        for (CardBill bill : openBills) {
            if (bill.isWithdrawalRequested()) {
                log.warn("탈퇴로 닫은 청구서에 결과를 확정하지 못한 출금 요청이 있음, 운영 확인(태그 YAMU-B{}) cardBillId={} userId={}",
                        bill.getId(), bill.getId(), userId);
            }
            bill.closeForWithdrawal();
        }
        for (Refund refund : openRefunds) {
            if (refund.isDepositRequested()) {
                log.warn("탈퇴로 닫은 환급에 결과를 확정하지 못한 입금 요청이 있음, 운영 확인 refundId={} userId={}",
                        refund.getId(), userId);
            }
            refund.closeForWithdrawal();
        }
        bills.flush();
        refunds.flush();
        return new Closed(openBills.stream().map(CardBill::getId).toList(), openRefunds.stream().map(Refund::getId).toList());
    }

    private void requireNothingInFlight(List<CardBill> openBills, List<Refund> openRefunds) {
        List<Long> billIds = openBills.stream()
                .filter(bill -> bill.isWithdrawalRequested() && withinGrace(bill.getFinRequestNo()))
                .map(CardBill::getId).toList();
        if (!billIds.isEmpty()) {
            throw new BusinessException(ErrorCode.BILL_OUTSTANDING, Map.of("billIds", billIds));
        }
        List<Long> refundIds = openRefunds.stream()
                .filter(refund -> refund.isDepositRequested() && withinGrace(refund.getFinRequestNo()))
                .map(Refund::getId).toList();
        if (!refundIds.isEmpty()) {
            throw new BusinessException(ErrorCode.REFUND_PENDING, Map.of("refundIds", refundIds));
        }
    }

    /** 요청 번호의 시각 + 유예 안인지. 번호에서 시각을 읽을 수 없으면 기다려도 풀리지 않으므로 유예가 지난 것으로 본다. */
    private boolean withinGrace(String finRequestNo) {
        try {
            return clock.instant().isBefore(WithdrawalRecovery.requestTime(finRequestNo).plus(approval.recoveryGrace()));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** 닫은 청구서·환급 번호. */
    public record Closed(List<Long> billIds, List<Long> refundIds) {}
}
