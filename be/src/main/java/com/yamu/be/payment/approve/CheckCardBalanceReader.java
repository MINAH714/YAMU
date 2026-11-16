package com.yamu.be.payment.approve;

import com.yamu.be.payment.approve.PaymentAccountReader.BankAccess;
import com.yamu.be.payment.bank.BankTransaction;
import com.yamu.be.payment.bank.SsafyTransferApi;
import com.yamu.be.payment.bank.TransferTag;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 체크카드 승인 출금 뒤 연결 계좌 잔액(승인 응답 {@code accountBalanceAfter}, S15P31E103-569).
 *
 * <p>SSAFY 출금 응답에는 거래 후 잔액이 없다. 그래서 거래내역에서 이 결제의 태그({@code YAMU-P{paymentId}})와 출금 요청 번호의
 * 조회 기간으로 그 출금 한 건을 찾아 거래 후 잔액을 읽는다(체크카드 취소 환불의 입금 뒤 잔액과 같은 방식). 잔액 조회 API는 쓰지 않는다:
 * 그 사이 다른 거래가 끼면 이 결제 직후 잔액이 아니다.
 *
 * <p>승인은 이미 저장된 뒤에 부르므로 최선 노력이다. 계좌를 다시 찾지 못함·내역에 아직 없음·한 건이 아님·금액이 출금액과 다름·
 * 조회 실패는 모두 null이고 다시 시도하지 않는다. 로그에는 결제 번호와 건수만 남긴다(잔액·계좌번호 금지).
 */
@Slf4j
@Component
public class CheckCardBalanceReader {

    private final PaymentAccountReader accounts;
    private final SsafyTransferApi transfer;

    public CheckCardBalanceReader(PaymentAccountReader accounts, SsafyTransferApi transfer) {
        this.accounts = accounts;
        this.transfer = transfer;
    }

    /**
     * @param userCardId 출금한 체크카드
     * @param approved   이 승인. 출금 요청 번호가 없으면(신용카드·0원 승인) 출금이 없었으므로 읽지 않는다
     * @return 이 출금 직후 잔액(원). 읽지 못하면 null
     */
    public Long balanceAfter(Long paymentId, Long userId, Long userCardId, ApprovalOutcome.Approved approved) {
        if (approved.finRequestNo() == null || approved.chargedAmount() <= 0) {
            return null;
        }
        try {
            Optional<BankAccess> access = accounts.forCard(userId, userCardId);
            if (access.isEmpty()) {
                log.info("승인 뒤 잔액을 읽을 계좌를 찾지 못함(응답 잔액 null) paymentId={}", paymentId);
                return null;
            }
            List<BankTransaction> found = transfer.findAllByTag(access.get().userKey(), access.get().accountNo(),
                    TransferTag.payment(paymentId), approved.finRequestNo());
            if (found.size() == 1 && found.get(0).amount() == approved.chargedAmount()) {
                return found.get(0).balanceAfter();
            }
            log.info("승인 출금 뒤 잔액을 거래내역에서 찾지 못함(응답 잔액 null) paymentId={} matches={}", paymentId,
                    found.size());
        } catch (RuntimeException e) {
            log.info("승인 출금 뒤 잔액 조회 실패(응답 잔액 null) paymentId={} error={}", paymentId,
                    e.getClass().getSimpleName());
        }
        return null;
    }
}
