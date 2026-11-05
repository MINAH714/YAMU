package com.yamu.be.payment.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.yamu.be.forecast.CardBillReader;
import com.yamu.be.forecast.CardBillReader.OutstandingBill;
import com.yamu.be.forecast.CardBillReader.Status;
import com.yamu.be.support.MySqlTestSupport;
import com.yamu.be.support.OwnershipFixtures;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

/**
 * 잔고예보와 맞춘 청구 계약({@link CardBillReader}, 2026-10-30)을 실제 MySQL로 확인한다.
 * 상태별 금액·출금 계좌·출금일 범위, 남의 청구, 고르지 않은 계좌.
 */
@Transactional
@TestPropertySource(properties = "spring.application.name=yamu-outstanding-card-bill-reader-test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OutstandingCardBillReaderTest extends MySqlTestSupport {

    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");

    @Autowired CardBillReader reader;
    @Autowired JdbcTemplate jdbc;

    OwnershipFixtures owners;
    BillFixtures bills;
    long user;
    long accountA;
    long accountB;
    long card;
    long merchant;
    LocalDate today;
    YearMonth month;

    @BeforeEach
    void setUp() {
        owners = new OwnershipFixtures(jdbc);
        bills = new BillFixtures(jdbc);
        user = owners.user();
        accountA = owners.account(user);
        accountB = owners.account(user);
        // 카드는 지금 B 계좌에 연결돼 있다. 확정 당시에는 A였던 청구서가 있다(확정 뒤 계좌를 바꾼 경우)
        card = owners.card(user, owners.product(), accountB, "ACTIVE");
        merchant = bills.merchant("테스트 카페");
        today = LocalDate.now(KOREA);
        month = YearMonth.from(today);
    }

    @Test
    void 상태마다_금액과_출금_계좌를_계약대로_돌려준다() {
        // 진행 중: 청구 전 30,000원 + 확정 전 취소로 빠진 5,000원 → 30,000원, 카드의 지금 계좌(B)
        long accumulating = bills.accumulating(user, card, month.plusMonths(1), today.plusDays(5));
        item(accumulating, month.plusMonths(1), 30_000L, "PENDING");
        item(accumulating, month.plusMonths(1), 5_000L, "EXCLUDED");
        // 확정: 출금일이 지났지만 출금 결과가 없음 → 확정액, 확정 당시 계좌(A)
        long confirmed = bills.confirmed(user, card, month, today.minusDays(2), accountA, 50_000L);
        // 미납: 40,000원 중 15,000원을 취소로 뺐다 → 남은 25,000원, 원래 출금일
        long unpaid = bills.unpaid(user, card, month.minusMonths(2), today.minusDays(40), accountA, 40_000L, 15_000L);
        // 출금 완료·미납이 취소로 0원이 된 청구서는 넣지 않는다
        bills.paid(user, card, month.minusMonths(1), today.minusDays(10), accountA, 20_000L, "FIN-1");
        bills.unpaid(user, card, month.minusMonths(3), today.minusDays(70), accountA, 10_000L, 10_000L);

        List<OutstandingBill> result = reader.outstanding(user, List.of(accountA, accountB), today.plusDays(10));

        assertThat(result).extracting(OutstandingBill::cardBillId, OutstandingBill::status, OutstandingBill::accountId,
                OutstandingBill::withdrawalDate, OutstandingBill::amount).containsExactly(
                tuple(unpaid, Status.UNPAID, accountA, today.minusDays(40), 25_000L),
                tuple(confirmed, Status.CONFIRMED, accountA, today.minusDays(2), 50_000L),
                tuple(accumulating, Status.ACCUMULATING, accountB, today.plusDays(5), 30_000L));
    }

    @Test
    void 고른_계좌의_청구만_돌려준다() {
        long accumulating = bills.accumulating(user, card, month.plusMonths(1), today.plusDays(5));
        item(accumulating, month.plusMonths(1), 30_000L, "PENDING");
        long confirmed = bills.confirmed(user, card, month, today.plusDays(1), accountA, 50_000L);

        assertThat(reader.outstanding(user, List.of(accountA), today.plusDays(10)))
                .extracting(OutstandingBill::cardBillId).containsExactly(confirmed);
        assertThat(reader.outstanding(user, List.of(accountB), today.plusDays(10)))
                .extracting(OutstandingBill::cardBillId).containsExactly(accumulating);
        assertThat(reader.outstanding(user, List.of(), today.plusDays(10))).isEmpty();
    }

    @Test
    void 출금일이_기간_뒤인_진행_중_확정_청구는_빼고_미납은_날짜와_상관없이_넣는다() {
        long later = bills.accumulating(user, card, month.plusMonths(1), today.plusDays(20));
        item(later, month.plusMonths(1), 30_000L, "PENDING");
        bills.confirmed(user, card, month, today.plusDays(20), accountA, 50_000L);
        long unpaid = bills.unpaid(user, card, month.minusMonths(1), today.plusDays(20), accountA, 40_000L, 0L);

        assertThat(reader.outstanding(user, List.of(accountA, accountB), today.plusDays(10)))
                .extracting(OutstandingBill::cardBillId).containsExactly(unpaid);
    }

    @Test
    void 회차가_없거나_모두_빠진_청구서와_남의_청구는_넣지_않는다() {
        bills.accumulating(user, card, month.plusMonths(1), today.plusDays(5));
        long allExcluded = bills.accumulating(user, card, month.plusMonths(2), today.plusDays(6));
        item(allExcluded, month.plusMonths(2), 9_000L, "EXCLUDED");
        long stranger = owners.user();
        long strangerAccount = owners.account(stranger);
        long strangerCard = owners.card(stranger, owners.product(), strangerAccount, "ACTIVE");
        bills.confirmed(stranger, strangerCard, month, today.plusDays(1), strangerAccount, 70_000L);

        assertThat(reader.outstanding(user, List.of(accountA, accountB, strangerAccount), today.plusDays(10)))
                .isEmpty();
    }

    @Test
    void 출금_계좌가_없는_카드의_진행_중_청구는_빼지_않고_계좌를_비운_채_돌려준다() {
        // 실제로는 생기지 않는 데이터다. 조용히 빼지 않고 넘겨 잔고예보가 계약 위반으로 실패하게 한다(ForecastServiceTest가 실패를 확인)
        long noAccountCard = owners.card(user, owners.product(), null, "ACTIVE");
        long orphan = bills.accumulating(user, noAccountCard, month.plusMonths(1), today.plusDays(5));
        long payment = bills.payment(user, noAccountCard, merchant, 30_000L, 1, LocalDateTime.now().minusDays(1));
        bills.item(payment, user, noAccountCard, orphan, 1, month.plusMonths(1), 30_000L, "PENDING");
        long confirmed = bills.confirmed(user, card, month, today.plusDays(1), accountA, 50_000L);

        assertThat(reader.outstanding(user, List.of(accountA, accountB), today.plusDays(10)))
                .extracting(OutstandingBill::cardBillId, OutstandingBill::accountId)
                .containsExactly(tuple(confirmed, accountA), tuple(orphan, null));
    }

    private void item(long bill, YearMonth billMonth, long principal, String status) {
        long payment = bills.payment(user, card, merchant, principal, 1, LocalDateTime.now().minusDays(1));
        bills.item(payment, user, card, bill, 1, billMonth, principal, status);
    }
}
