package com.yamu.be.payment.cancel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.payment.IdempotencyKeys;
import com.yamu.be.payment.barcode.Barcode;
import com.yamu.be.payment.barcode.BarcodeStatus;
import com.yamu.be.payment.barcode.BarcodeStore;
import com.yamu.be.payment.domain.PaymentTransaction;
import com.yamu.be.payment.domain.PaymentTransactionRepository;
import com.yamu.be.payment.pos.PosTerminalResolver;
import com.yamu.be.payment.request.MerchantContextSnapshot;
import java.time.Clock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * POS 결제 취소(S15P31E103-424). 11/10 QA 회의 결정: POS 결제 내역에서 한 건을 골라 고객 결제 바코드를 찍으면 취소한다.
 * 취소 전용 바코드는 만들지 않는다(바코드가 서버 주소를 넘겨주는 역할이라).
 *
 * <pre>
 * ① POS 키 확인({@code PosKeyInterceptor}가 먼저 본다), Idempotency-Key 필수
 * ② 바코드로 고객을 찾는다. 없으면 404 BARCODE_NOT_FOUND
 * ③ 앱 취소와 같은 {@link PaymentCancelService}로 취소한다. 같은 키의 처음 응답이 있으면 그대로 돌려준다
 *    새로 처리할 때만 멱등 잠금 안에서 먼저 본다:
 *    바코드가 만료 전 ISSUED인지(만료 410, 스캔·사용 409) → 결제가 그 고객의 것이고 이 posCode가 만든 것인지(아니면 404)
 * ④ 취소가 끝나면 바코드를 닫는다(ISSUED → EXPIRED). 닫기 실패는 로그만 남긴다(취소는 이미 끝남)
 * ⑤ 응답은 POS 영수증 값만. 앱 응답의 실적·청구·잔액은 넣지 않는다
 * </pre>
 *
 * <p>결정(2026-11-11, 임시): 바코드는 ISSUED만 받고 취소가 성공한 뒤 닫는다. 금융망 실패·결과 불명이면 바코드를 그대로 두어
 * 유효 시간(60초) 안에 같은 바코드로 다시 시도할 수 있다. 다른 고객의 바코드, 다른 POS는 모두 404(남의 결제는 없는 것과 같다).
 *
 * <p>깨질 수 있는 곳: 같은 결제를 앱과 POS가 동시에 취소하면 {@link PaymentCancelService}의 행 잠금·정산 잠금으로 한 번만 취소되고
 * 뒤 요청은 409 ALREADY_CANCELED 또는 처리 중이다. 같은 키 재전송은 바코드가 닫혀 있어도 처음 응답을 받는다(바코드 보관 10분 안).
 *
 * <p>한계: 한 바코드로 같은 고객의 다른 결제 두 건을 동시에 취소하면, 둘 다 닫기 전에 ISSUED를 읽어 둘 다 취소될 수 있다.
 * 둘 다 그 고객의 결제라 남의 돈이 움직이지는 않는다. 바코드 보관(10분)이 끝난 뒤의 재전송은 404 BARCODE_NOT_FOUND다.
 */
@Slf4j
@Service
public class PosPaymentCancelService {

    private static final PaymentCancelRequest NO_REASON = new PaymentCancelRequest(null);

    private final PosTerminalResolver terminals;
    private final BarcodeStore barcodes;
    private final PaymentCancelService cancels;
    private final PaymentTransactionRepository payments;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public PosPaymentCancelService(PosTerminalResolver terminals, BarcodeStore barcodes, PaymentCancelService cancels,
            PaymentTransactionRepository payments, ObjectMapper objectMapper, Clock clock) {
        this.terminals = terminals;
        this.barcodes = barcodes;
        this.cancels = cancels;
        this.payments = payments;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public PosPaymentCancelResult cancel(String posKey, Long paymentId, PosPaymentCancelRequest request,
            String idempotencyKey) {
        terminals.authenticate(posKey);
        String key = IdempotencyKeys.require(idempotencyKey);
        String token = request.barcodeToken();
        Long userId = barcodes.find(token)
                .orElseThrow(() -> new BusinessException(ErrorCode.BARCODE_NOT_FOUND))
                .userId();

        cancels.cancel(userId, paymentId, NO_REASON, key,
                () -> verify(token, userId, paymentId, request.posCode()));
        close(token, paymentId);
        return result(paymentId, userId);
    }

    /** 새로 취소할 때만 본다. 바코드 상태는 잠금 안에서 다시 읽는다. */
    private void verify(String token, Long userId, Long paymentId, String posCode) {
        Barcode barcode = barcodes.find(token)
                .orElseThrow(() -> new BusinessException(ErrorCode.BARCODE_NOT_FOUND));
        BarcodeStatus status = barcode.statusAt(clock.instant());
        if (status == BarcodeStatus.EXPIRED) {
            throw new BusinessException(ErrorCode.BARCODE_EXPIRED);
        }
        if (status != BarcodeStatus.ISSUED) {
            throw new BusinessException(ErrorCode.BARCODE_ALREADY_USED);
        }
        requireOwnPosPayment(paymentId, userId, posCode);
    }

    /** 그 고객의 결제이고 이 posCode가 만든 결제. 아니면 404. */
    private void requireOwnPosPayment(Long paymentId, Long userId, String posCode) {
        boolean found = payments.findById(paymentId)
                .filter(payment -> userId.equals(payment.getUserId()))
                .flatMap(payment -> MerchantContextSnapshot.of(payment, objectMapper))
                .filter(context -> posCode.equals(context.posCode()))
                .isPresent();
        if (!found) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
    }

    private void close(String token, Long paymentId) {
        try {
            barcodes.closeIssued(token);
        } catch (RuntimeException e) {
            log.warn("POS 취소 뒤 바코드 닫기 실패(취소는 완료) paymentId={}", paymentId, e);
        }
    }

    /** 취소된 결제를 다시 읽어 POS 영수증 값을 만든다. 재전송이면 처음 취소한 결제다. */
    private PosPaymentCancelResult result(Long paymentId, Long userId) {
        PaymentTransaction payment = payments.findById(paymentId)
                .filter(row -> userId.equals(row.getUserId()))
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        String orderNo = MerchantContextSnapshot.of(payment, objectMapper)
                .map(MerchantContextSnapshot::orderNo).orElse(null);
        return new PosPaymentCancelResult(payment.getId(), orderNo, payment.getStatus(), payment.getCanceledAt(),
                payment.getRequestedAmount(), payment.getApprovedAmount());
    }
}
