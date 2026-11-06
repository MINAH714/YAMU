package com.yamu.be.forecast;

import com.yamu.be.card.domain.CardType;
import com.yamu.be.global.calendar.BusinessDayCalendar;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * 카드 결제 한 건이 계좌에서 빠지는 일정(회차별 출금일·금액)을 계산한다(Task 7.2.1). DB를 읽거나 쓰지 않으므로
 * 예상 일정은 실제 결제·청구서에 저장되지 않는다.
 *
 * <ul>
 *   <li>체크카드: 구매일에 한 번 바로 빠진다. 구매일이 주말·공휴일이어도 그날이다.</li>
 *   <li>신용카드(POL-005): 이용기간 마감일 = 결제일 − N일(달력으로 거꾸로 셈). 구매일이 그달 마감일 이전(포함)이면 그달 결제일에,
 *       아니면 다음 달 결제일에 청구된다. 할부(POL-013)의 k번째 회차는 첫 청구 월에서 k−1달 뒤 결제일이다.
 *       회차마다 원래 결제일을 만든 뒤 비영업일이면 다음 영업일로 옮긴다. 옮긴 날이 다음 달이어도 청구 월은 그대로다.</li>
 *   <li>할부 금액은 똑같이 나누고 원 단위 나머지는 마지막 회차에 붙인다(수수료 0원).</li>
 * </ul>
 *
 * <p>amount는 승인 금액이다. 즉시할인은 부르는 쪽이 이미 뺀 값이고, 청구할인은 반영하지 않는다(청구 단위 할인은 BE 2 청구가 맡음).
 * 결제 조건(할부 5만 원 이상, 체크카드는 일시불만 등)의 업무 검증은 할인 전 원래 결제 금액으로 결제 쪽 조건 검증이 맡는다.
 * 여기서는 계산이 성립하지 않는 값만 거절한다.
 */
public final class BillingSchedule {
    /** 카드 상품에 이용기간 마감 일수가 없을 때 쓰는 값(청구서 명세 "1차 14일"). */
    static final int DEFAULT_CUTOFF_DAYS = 14;
    private static final int MAX_INSTALLMENT_MONTHS = 12;
    /** 카드 등록·수정 API와 같은 결제일 범위(명세 「카드 등록」 FR-067). 모든 달에 있는 날이라 말일 보정이 필요 없다. */
    private static final int MAX_PAYMENT_DAY = 27;
    private static final int MAX_CUTOFF_DAYS = 31;

    private final BusinessDayCalendar calendar;

    public BillingSchedule(BusinessDayCalendar calendar) {
        this.calendar = calendar;
    }

    /**
     * 결제 한 건의 회차별 출금 일정을 돌려준다.
     *
     * @param cardType          카드 종류
     * @param paymentDay        신용카드 결제일(1~27). 체크카드는 쓰지 않는다
     * @param billingCutoffDays 신용카드 이용기간 마감 일수 N(1~31). null이면 14일
     * @param purchaseDate      한국 날짜 기준 구매일
     * @param amount            승인 금액(원). 회차마다 1원 이상이 되도록 할부 개월 수 이상
     * @param installmentMonths 일시불 1, 할부 2~12. 체크카드는 1만
     * @return 회차 순서대로의 출금 일정
     * @throws IllegalArgumentException 계산이 성립하지 않는 값
     */
    public List<Withdrawal> schedule(CardType cardType, Integer paymentDay, Integer billingCutoffDays,
            LocalDate purchaseDate, long amount, int installmentMonths) {
        if (cardType == null || purchaseDate == null) {
            throw new IllegalArgumentException("카드 종류와 구매일이 필요하다.");
        }
        if (amount <= 0) {
            throw new IllegalArgumentException("금액은 1원 이상이어야 한다.");
        }
        if (installmentMonths < 1 || installmentMonths > MAX_INSTALLMENT_MONTHS) {
            throw new IllegalArgumentException("할부 개월 수는 1(일시불)~12다: " + installmentMonths);
        }
        // 회차마다 1원 이상 빠져야 한다. 0원 출금 회차는 시뮬레이션·예보에 보일 의미가 없다.
        if (amount < installmentMonths) {
            throw new IllegalArgumentException("할부 회차마다 1원 이상이어야 한다.");
        }
        if (cardType == CardType.CHECK) {
            if (installmentMonths != 1) {
                throw new IllegalArgumentException("체크카드는 일시불만 된다.");
            }
            return List.of(new Withdrawal(1, 1, null, purchaseDate, purchaseDate, amount));
        }
        if (paymentDay == null || paymentDay < 1 || paymentDay > MAX_PAYMENT_DAY) {
            throw new IllegalArgumentException("신용카드 결제일은 1~27이어야 한다: " + paymentDay);
        }
        int cutoffDays = billingCutoffDays == null ? DEFAULT_CUTOFF_DAYS : billingCutoffDays;
        if (cutoffDays < 1 || cutoffDays > MAX_CUTOFF_DAYS) {
            throw new IllegalArgumentException("이용기간 마감 일수는 1~31이어야 한다: " + cutoffDays);
        }

        // TODO(S15P31E103-205): 결제일을 바꾸면 다음 청구서부터 적용된다(명세 「청구서 목록」). 지금은 현재 결제일로만 계산한다.
        //  진행 중 청구서 데이터는 생겼지만, BE 1 카드 수정은 결제일을 바로 바꾸고 BE 2 청구서 작성도 현재 결제일로 만든다.
        //  언제부터 새 결제일을 쓸지 BE 1·BE 2·BE 4가 정하면 그 규칙으로 바꾼다(CardManagementService.update 주석).
        YearMonth firstMonth = YearMonth.from(purchaseDate);
        while (purchaseDate.isAfter(firstMonth.atDay(paymentDay).minusDays(cutoffDays))) {
            firstMonth = firstMonth.plusMonths(1);
        }
        long base = amount / installmentMonths;
        List<Withdrawal> withdrawals = new ArrayList<>();
        for (int no = 1; no <= installmentMonths; no++) {
            // 이전 회차의 실제 출금일이 아니라 청구 월의 원래 결제일에서 다시 시작한다.
            YearMonth billingMonth = firstMonth.plusMonths(no - 1L);
            LocalDate paymentDate = billingMonth.atDay(paymentDay);
            long installmentAmount = no < installmentMonths ? base : amount - base * (installmentMonths - 1);
            withdrawals.add(new Withdrawal(no, installmentMonths, billingMonth, paymentDate,
                    businessDayOnOrAfter(paymentDate), installmentAmount));
        }
        return List.copyOf(withdrawals);
    }

    private LocalDate businessDayOnOrAfter(LocalDate date) {
        LocalDate businessDay = calendar.businessDayOnOrAfter(date);
        if (businessDay == null || businessDay.isBefore(date)) {
            throw new IllegalStateException("영업일 달력이 계약과 다른 날을 돌려줬다.");
        }
        return businessDay;
    }

    /**
     * 출금 한 회차.
     *
     * @param installmentNo     회차(1부터)
     * @param installmentMonths 전체 회차 수(일시불 1)
     * @param billingMonth      청구 월. 체크카드는 청구가 없어 null
     * @param paymentDate       원래 결제일(체크카드는 구매일)
     * @param withdrawalDate    실제 출금일. 원래 결제일이 비영업일이면 다음 영업일
     * @param amount            이 회차 출금액(원)
     */
    public record Withdrawal(int installmentNo, int installmentMonths, YearMonth billingMonth, LocalDate paymentDate,
            LocalDate withdrawalDate, long amount) {}
}
