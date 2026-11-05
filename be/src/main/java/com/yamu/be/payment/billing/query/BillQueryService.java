package com.yamu.be.payment.billing.query;

import com.yamu.be.account.BankNames;
import com.yamu.be.card.domain.CardType;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import com.yamu.be.payment.PaymentPlan;
import com.yamu.be.payment.billing.BillItemDiscountReader;
import com.yamu.be.payment.billing.BillStatus;
import com.yamu.be.payment.billing.BillingCycle;
import com.yamu.be.payment.billing.confirm.BillingDiscountSource;
import com.yamu.be.payment.billing.query.BillListResponse.NextWithdrawal;
import com.yamu.be.payment.billing.query.BillListResponse.Summary;
import com.yamu.be.payment.billing.query.BillListResponse.UsagePeriod;
import com.yamu.be.payment.billing.query.BillListResponse.WithdrawalAccount;
import com.yamu.be.payment.billing.query.BillQueryReader.BillRow;
import com.yamu.be.payment.billing.query.BillQueryReader.ItemRow;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 신용카드 청구서 조회(노션 API 명세 V2 「청구서 목록」·「청구서 상세」, Task 5.4.1). 읽기만 한다.
 *
 * <p>남의 청구서·카드는 없는 것과 같은 404 RESOURCE_NOT_FOUND다(명세 1장). 체크카드는 청구서가 없어 빈 목록이다.
 */
@Service
@Transactional(readOnly = true)
public class BillQueryService {

    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
    /** 청구 월을 고르지 않은 목록에 넣는 끝난 청구서의 범위. 한국 기준 이번 달을 포함한 최근 개월 수 */
    static final int RECENT_MONTHS = 3;
    static final String INTEREST_FREE = "YAMU_INTEREST_FREE";
    static final String WITHDRAWAL_SUCCESS = "SUCCESS";

    private final BillQueryReader reader;
    private final BillingDiscountSource discounts;
    private final BillItemDiscountReader confirmedDiscounts;
    private final Clock clock;

    BillQueryService(BillQueryReader reader, BillingDiscountSource discounts, BillItemDiscountReader confirmedDiscounts,
            Clock clock) {
        this.reader = reader;
        this.discounts = discounts;
        this.confirmedDiscounts = confirmedDiscounts;
        this.clock = clock;
    }

    /**
     * 청구서 목록. 청구 월을 고르지 않으면 끝나지 않은 청구서(진행 중·확정·미납)는 날짜와 상관없이 모두 넣고,
     * 끝난 청구서(출금 완료·CANCELED)는 한국 기준 이번 달을 포함한 최근 {@value #RECENT_MONTHS}개월 청구 월만 넣는다.
     *
     * @param userCardId 카드 필터. 없는 카드·남의 카드는 404, 체크카드는 빈 목록
     */
    public BillListResponse list(long userId, Long userCardId, YearMonth billingMonth, BillStatus status) {
        if (userCardId != null) {
            CardType type = reader.cardType(userId, userCardId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
            if (type == CardType.CHECK) {
                return new BillListResponse(List.of(), new Summary(null, 0));
            }
        }
        LocalDate recentFrom = YearMonth.from(today()).minusMonths(RECENT_MONTHS - 1).atDay(1);
        List<BillListResponse.Item> items = reader.bills(userId, userCardId, status, billingMonth, recentFrom).stream()
                .map(this::item).toList();
        return new BillListResponse(items, summary(reader.openBills(userId, userCardId)));
    }

    /** 청구서 상세. 없는 청구서·남의 청구서는 404. */
    public BillDetailResponse detail(long userId, long billId) {
        BillRow bill = reader.bill(userId, billId).orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        List<BillDetailResponse.Item> items = reader.items(userId, billId).stream().map(BillQueryService::item).toList();
        List<BillDetailResponse.Refund> refunds = reader.refunds(userId, billId).stream()
                .map(r -> new BillDetailResponse.Refund(r.refundId(), r.billItemId(), r.paymentId(), r.amount(),
                        r.status(), r.completedAt()))
                .toList();
        return new BillDetailResponse(bill.billId(), bill.userCardId(), nickname(bill), maskedCard(bill.cardLast4()),
                YearMonth.from(bill.billingMonth()), usagePeriod(bill), bill.periodEnd(), paymentDate(bill),
                bill.withdrawalDate(), bill.status(),
                new BillDetailResponse.Amounts(bill.itemAmount(), billingDiscount(bill), expectedAmount(bill),
                        finalAmount(bill), unpaidAmount(bill)),
                withdrawalAccount(bill), items, discounts(userId, bill), withdrawal(bill), refunds, history(bill));
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), KOREA);
    }

    private BillListResponse.Item item(BillRow bill) {
        return new BillListResponse.Item(bill.billId(), bill.userCardId(), nickname(bill),
                YearMonth.from(bill.billingMonth()), usagePeriod(bill), bill.periodEnd(), paymentDate(bill),
                bill.withdrawalDate(), bill.status(), bill.itemCount(), bill.itemAmount(), billingDiscount(bill),
                expectedAmount(bill),
                finalAmount(bill), unpaidAmount(bill), bill.confirmedAt(), bill.paidAt(), withdrawalAccount(bill));
    }

    private static BillDetailResponse.Item item(ItemRow item) {
        int totalSeq = item.installmentMonths() == null ? 1 : item.installmentMonths();
        PaymentPlan plan = totalSeq > 1 ? PaymentPlan.INSTALLMENT : PaymentPlan.LUMP_SUM;
        long fee = item.feeAmount() == null ? 0 : item.feeAmount();
        long amount = item.billedAmount() != null ? item.billedAmount() : item.principalAmount() + fee;
        // 모의 무이자(POL-013) 회차는 승인 때 수수료를 0원으로 확정해 둔다. 수수료가 0원으로 확정된 할부 회차만 무이자로 표시한다
        String policy = plan == PaymentPlan.INSTALLMENT && item.feeAmount() != null && item.feeAmount() == 0
                ? INTEREST_FREE : null;
        return new BillDetailResponse.Item(item.billItemId(), item.paymentId(), item.approvedAt(), item.merchantName(),
                plan, item.installmentNo(), totalSeq, amount, item.status(), item.principalAmount(), item.feeAmount(),
                policy);
    }

    /**
     * 다음 출금: 출금일이 가장 빠른 진행 중·확정 청구서(금액이 0원이면 출금이 없어 뺀다). 확정 청구서는 출금일이 지났어도
     * 아직 출금 결과가 없으면 넣는다(출금 처리 중).
     */
    private Summary summary(List<BillRow> open) {
        NextWithdrawal next = open.stream()
                .filter(b -> b.status() == BillStatus.ACCUMULATING || b.status() == BillStatus.CONFIRMED)
                .filter(b -> withdrawalAmount(b) > 0)
                .min(Comparator.comparing(BillRow::withdrawalDate).thenComparing(BillRow::billId))
                .map(b -> new NextWithdrawal(b.billId(), b.withdrawalDate(), withdrawalAmount(b)))
                .orElse(null);
        int unpaid = (int) open.stream().filter(b -> b.status() == BillStatus.UNPAID).count();
        return new Summary(next, unpaid);
    }

    private long withdrawalAmount(BillRow bill) {
        Long amount = bill.status() == BillStatus.ACCUMULATING ? expectedAmount(bill) : finalAmount(bill);
        return amount == null ? 0 : amount;
    }

    /** 진행 중일 때만: 청구 전 회차 원금 합계 − 예상 청구할인(확정 때와 같은 배분, Task 5.4.7). */
    private Long expectedAmount(BillRow bill) {
        return bill.status() == BillStatus.ACCUMULATING ? bill.pendingAmount() - billingDiscount(bill) : null;
    }

    /**
     * 청구할인. 진행 중이면 지금 청구 전 회차로 계산한 예상값(저장하지 않음), 확정 뒤에는 확정 때 회차에 나눈 합계다.
     * 같은 청구서를 한 요청에서 여러 번 물을 수 있지만 진행 중 청구서는 카드마다 한두 장이라 그대로 계산한다.
     */
    private long billingDiscount(BillRow bill) {
        if (bill.status() == BillStatus.ACCUMULATING) {
            return BillingDiscountSource.Allocation.total(discounts.allocate(bill.billId()));
        }
        return bill.discountAmount();
    }

    /** 확정한 청구할인(혜택별). 진행 중 청구서는 아직 확정한 할인이 없어 빈 목록이다. */
    private List<BillDetailResponse.Discount> discounts(long userId, BillRow bill) {
        if (bill.status() == BillStatus.ACCUMULATING) {
            return List.of();
        }
        return confirmedDiscounts.lines(userId, bill.billId()).stream()
                .map(d -> new BillDetailResponse.Discount(d.paymentBenefitId(), d.paymentId(), d.name(), d.amount(),
                        d.confirmedAt()))
                .toList();
    }

    private static Long finalAmount(BillRow bill) {
        return bill.status() == BillStatus.ACCUMULATING ? null : bill.confirmedAmount();
    }

    /**
     * 남은 미납액 = 확정액 − 출금액 − 취소로 뺀 미납 원금({@code CardBill.outstandingUnpaid}, 잔고예보의 미납 금액과 같은 식).
     * 미납(UNPAID)과 미납이 취소로 정리된 CANCELED(0원)에만 낸다.
     */
    private static Long unpaidAmount(BillRow bill) {
        if (bill.status() != BillStatus.UNPAID && bill.status() != BillStatus.CANCELED) {
            return null;
        }
        long confirmed = bill.confirmedAmount() == null ? 0 : bill.confirmedAmount();
        return confirmed - bill.paidAmount() - bill.canceledUnpaidPrincipal();
    }

    private static String nickname(BillRow bill) {
        return bill.cardAlias() != null ? bill.cardAlias() : bill.productName();
    }

    private static UsagePeriod usagePeriod(BillRow bill) {
        return new UsagePeriod(bill.periodStart(), bill.periodEnd());
    }

    /**
     * 결제일은 따로 저장하지 않는다. 이용기간 마감 = 결제일 − N일({@link BillingCycle})이므로 마감일에 상품의 N을 더한다.
     * 결제일을 바꿔도 이미 만든 청구서의 마감일은 그대로라 그 청구서의 결제일이 나온다.
     */
    private static LocalDate paymentDate(BillRow bill) {
        int cutoffDays = bill.cutoffDays() == null ? BillingCycle.DEFAULT_CUTOFF_DAYS : bill.cutoffDays();
        return bill.periodEnd().plusDays(cutoffDays);
    }

    private static WithdrawalAccount withdrawalAccount(BillRow bill) {
        if (bill.accountId() == null) {
            return null;
        }
        return new WithdrawalAccount(bill.accountId(), BankNames.of(bill.bankCode()), maskedAccount(bill.accountLast4()));
    }

    /** 출금을 시도하기 전(진행 중·확정)에는 null. 임시: 미납 사유·출금 뒤 잔액은 저장하지 않아 null이다. */
    private static BillDetailResponse.Withdrawal withdrawal(BillRow bill) {
        if (bill.paidAt() != null) {
            return new BillDetailResponse.Withdrawal(bill.paidAt(), WITHDRAWAL_SUCCESS, null, bill.finTransactionNo(),
                    null);
        }
        if (bill.failedAt() != null) {
            return new BillDetailResponse.Withdrawal(bill.failedAt(), null, null, null, null);
        }
        return null;
    }

    /** 임시: 상태 이력 표가 없어 청구서에 남은 시각으로 만든다. */
    private static List<BillDetailResponse.History> history(BillRow bill) {
        List<BillDetailResponse.History> history = new ArrayList<>();
        history.add(new BillDetailResponse.History(BillStatus.ACCUMULATING, bill.createdAt()));
        if (bill.confirmedAt() != null) {
            history.add(new BillDetailResponse.History(BillStatus.CONFIRMED, bill.confirmedAt()));
        }
        if (bill.failedAt() != null) {
            history.add(new BillDetailResponse.History(BillStatus.UNPAID, bill.failedAt()));
        }
        if (bill.paidAt() != null) {
            history.add(new BillDetailResponse.History(BillStatus.PAID, bill.paidAt()));
        }
        history.sort(Comparator.comparing(BillDetailResponse.History::at));
        return history;
    }

    private static String maskedCard(String last4) {
        return "****-****-****-" + (last4 == null ? "****" : last4);
    }

    /** 계좌 API(AccountManagementService)와 같은 형식 */
    private static String maskedAccount(String last4) {
        return "****-****-****-" + (last4 == null ? "****" : last4);
    }
}
