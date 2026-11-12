package com.yamu.be.payment.result;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.payment.approve.ApprovalNumbers;
import com.yamu.be.payment.domain.PaymentStatus;
import com.yamu.be.payment.domain.PaymentTransaction;
import com.yamu.be.payment.domain.PaymentTransactionRepository;
import com.yamu.be.payment.lookup.PaymentCardLookup;
import com.yamu.be.payment.merchant.MerchantLookup;
import com.yamu.be.payment.pos.PosProperties;
import com.yamu.be.payment.pos.PosTerminalResolver;
import com.yamu.be.payment.request.MerchantContextSnapshot;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * POS 결제 내역(S15P31E103-423). 11/10 QA 회의에서 결제 취소를 POS가 하기로 바뀌어, 취소할 결제를 고를 목록이 필요해졌다.
 *
 * <pre>
 * ① POS 키·posCode 확인 → posCode의 브랜드로 가맹점을 찾는다(설정에 없는 posCode는 404 MERCHANT_NOT_FOUND)
 * ② 그 가맹점의 최근 7일 APPROVED·CANCELED 결제를 결제 번호 내림차순으로 100건씩 읽는다
 * ③ 거래 조건 스냅샷의 posCode가 같은 결제만 남기고 50건이 차면 멈춘다
 * </pre>
 *
 * <p>결정(2026-11-11, 임시): 결제를 만든 POS만 본다(결과 조회와 같다). 기간 7일·최대 50건, 페이지 없음.
 * 승인·취소 결제만 보이고 거절·만료·진행 중 결제는 넣지 않는다.
 *
 * <p>한계: posCode는 스냅샷 JSON에만 있어 SQL로 거르지 못한다. 같은 브랜드에 POS가 많고 다른 POS의 결제가 몰리면 여러 번 읽는다
 * (7일 안의 그 가맹점 결제 수만큼). POS 키는 하나라 키와 posCode를 아는 쪽은 그 POS의 내역을 볼 수 있다({@code PosKeyInterceptor}).
 */
@Service
public class PosPaymentHistoryService {

    static final int MAX_ITEMS = 50;
    static final Duration PERIOD = Duration.ofDays(7);
    static final Set<PaymentStatus> STATUSES = Set.of(PaymentStatus.APPROVED, PaymentStatus.CANCELED);

    private final PosTerminalResolver terminals;
    private final MerchantLookup merchants;
    private final PaymentTransactionRepository payments;
    private final PaymentCardLookup cards;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public PosPaymentHistoryService(PosTerminalResolver terminals, MerchantLookup merchants,
            PaymentTransactionRepository payments, PaymentCardLookup cards, ObjectMapper objectMapper, Clock clock) {
        this.terminals = terminals;
        this.merchants = merchants;
        this.payments = payments;
        this.cards = cards;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public PosPaymentHistory list(String posKey, String posCode) {
        PosProperties.Terminal terminal = terminals.resolve(posKey, posCode);
        Long merchantId = merchants.findByBrandCode(terminal.brandCode())
                .orElseThrow(() -> new BusinessException(ErrorCode.MERCHANT_NOT_FOUND))
                .merchantId();
        // DB 시각은 마이크로초까지라 같은 단위로 맞춘다(7일 정각에 요청한 결제가 나노초 차이로 빠지지 않게)
        LocalDateTime from = LocalDateTime.ofInstant(clock.instant().minus(PERIOD), ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.MICROS);

        Map<Long, Optional<PaymentCardLookup.ReceiptCard>> receiptCards = new HashMap<>();
        List<PosPaymentHistory.Item> items = new ArrayList<>();
        long beforeId = Long.MAX_VALUE;
        while (items.size() < MAX_ITEMS) {
            List<PaymentTransaction> page = payments
                    .findTop100ByMerchantIdAndStatusInAndRequestedAtGreaterThanEqualAndIdLessThanOrderByIdDesc(
                            merchantId, STATUSES, from, beforeId);
            for (PaymentTransaction payment : page) {
                Optional<MerchantContextSnapshot> context = MerchantContextSnapshot.of(payment, objectMapper)
                        .filter(snapshot -> posCode.equals(snapshot.posCode()));
                if (context.isPresent() && items.size() < MAX_ITEMS) {
                    items.add(item(payment, context.get().orderNo(), receiptCards));
                }
            }
            if (page.size() < 100) {
                break;
            }
            beforeId = page.get(page.size() - 1).getId();
        }
        return new PosPaymentHistory(items);
    }

    private PosPaymentHistory.Item item(PaymentTransaction payment, String orderNo,
            Map<Long, Optional<PaymentCardLookup.ReceiptCard>> receiptCards) {
        LocalDateTime approvedAt = payment.getApprovedAt();
        String approvalNo = approvedAt == null ? null : ApprovalNumbers.of(approvedAt, payment.getId());
        Long charged = approvedAt == null ? null : payment.getApprovedAmount();
        Long instantDiscount = charged == null ? null : payment.getRequestedAmount() - charged;
        PosPaymentResult.Card card = PosReceiptCards.of(payment,
                userCardId -> receiptCards.computeIfAbsent(userCardId, cards::findReceiptCard));
        return new PosPaymentHistory.Item(payment.getId(), orderNo, payment.getStatus(), approvalNo, approvedAt,
                payment.getCanceledAt(), payment.getRequestedAmount(), charged, instantDiscount, card,
                payment.getStatus() == PaymentStatus.APPROVED);
    }
}
