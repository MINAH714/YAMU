package com.yamu.app.feature.billing.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/** 청구서 · 환급 응답 → 화면 값. 금액은 서버 값 그대로 고르기만 하고 앱이 셈하지 않는다 */
class BillingMapperTest {

    /** 출금일(11/14 · 11/16)보다 앞 — 이 날짜를 넘기면 확정 청구서가 출금 완료로 바뀐다 */
    private val TODAY = LocalDate.of(2026, 11, 10)

    private fun bill(
        status: String?,
        expected: Long? = null,
        final: Long? = null,
        unpaid: Long? = null,
        item: Long = 50_000,
        payment: String? = "2026-11-14",
        withdrawal: String? = "2026-11-14",
        today: LocalDate = TODAY,
    ) = BillDto(
        billId = 1, userCardId = 11, nickname = "톡톡", billingMonth = "2026-11",
        usagePeriod = UsagePeriodDto("2026-10-01", "2026-10-31"), closingDate = "2026-10-31",
        paymentDate = payment, withdrawalDate = withdrawal, status = status, itemAmount = item,
        expectedAmount = expected, finalAmount = final, unpaidAmount = unpaid,
    ).toBill(today)!!

    // ── 출금일이 지난 청구서(출금 배치가 꺼진 서버) ──

    @Test fun `출금일이 지난 확정 청구서는 출금 완료 — 그날 출금으로`() {
        val b = bill("CONFIRMED", final = 87_000, withdrawal = "2026-11-16", today = LocalDate.of(2026, 11, 17))
        assertEquals(BillState.PAID, b.state)
        assertEquals("출금 완료", b.state.label)
        assertEquals(87_000L, b.amount)
        assertEquals("11/16 출금", b.rowLine())
        assertEquals("11월 16일(월)에 출금했어요", b.statusLine())
    }

    @Test fun `출금일 당일은 BE 값 그대로 — 07시 배치 전후를 가를 수 없다`() {
        assertEquals(BillState.CONFIRMED, bill("CONFIRMED", final = 1, withdrawal = "2026-11-16", today = LocalDate.of(2026, 11, 16)).state)
    }

    @Test fun `출금일이 지난 쌓이는 중 청구서도 출금 완료 · 예상 표시는 뗀다`() {
        val b = bill("ACCUMULATING", expected = 47_000, withdrawal = "2026-11-16", today = LocalDate.of(2026, 11, 17))
        assertEquals(BillState.PAID, b.state)
        assertEquals(47_000L, b.amount)
        assertFalse(b.isEstimate)
    }

    @Test fun `미납 · 정산 끝 · 모르는 상태는 출금일이 지나도 그대로`() {
        val after = LocalDate.of(2026, 11, 20)
        assertEquals(BillState.UNPAID, bill("UNPAID", final = 1, unpaid = 1, withdrawal = "2026-11-16", today = after).state)
        assertEquals(BillState.CANCELED, bill("CANCELED", final = 1, unpaid = 0, withdrawal = "2026-11-16", today = after).state)
        assertEquals(BillState.UNKNOWN, bill("WHAT", final = 1, withdrawal = "2026-11-16", today = after).state)
        assertEquals(BillState.CONFIRMED, bill("CONFIRMED", final = 1, withdrawal = null, today = after).state)
    }

    @Test fun `다음 출금이 지난 청구서면 목록에 남은 청구서 중 가장 빠른 것으로`() {
        val list = BillListDto(
            items = listOf(
                BillDto(billId = 3, status = "ACCUMULATING", withdrawalDate = "2026-12-14", expectedAmount = 30_000),
                BillDto(billId = 2, status = "CONFIRMED", withdrawalDate = "2026-11-16", finalAmount = 87_000),
            ),
            summary = BillSummaryDto(BillNextWithdrawalDto(2, "2026-11-16", 87_000)),
        ).toBillList(LocalDate.of(2026, 11, 17))
        assertEquals(NextBillWithdrawal(3, LocalDate.of(2026, 12, 14), 30_000), list.next)
        assertTrue(list.nextIsEstimate)
    }

    @Test fun `다음 출금이 지났고 남은 청구서도 없으면 요약에서 뺀다 · 오늘 이후면 서버 값 그대로`() {
        val past = BillListDto(
            items = listOf(BillDto(billId = 2, status = "CONFIRMED", withdrawalDate = "2026-11-16", finalAmount = 87_000)),
            summary = BillSummaryDto(BillNextWithdrawalDto(2, "2026-11-16", 87_000)),
        )
        assertNull(past.toBillList(LocalDate.of(2026, 11, 17)).next)
        assertEquals(NextBillWithdrawal(2, LocalDate.of(2026, 11, 16), 87_000), past.toBillList(LocalDate.of(2026, 11, 16)).next)
    }

    @Test fun `상세도 출금일이 지난 확정 청구서는 출금 완료`() {
        val d = BillDetailDto(billId = 2, status = "CONFIRMED", withdrawalDate = "2026-11-16").toBillDetail(2, LocalDate.of(2026, 11, 17))
        assertEquals(BillState.PAID, d.bill.state)
    }

    // ── 상태별로 크게 보여 줄 금액 ──

    @Test fun `쌓이는 중은 예상 청구액에 '예상'`() {
        val b = bill("ACCUMULATING", expected = 47_000)
        assertEquals(47_000L, b.amount)
        assertTrue(b.isEstimate)
        assertEquals("예상", b.amountNote())
    }

    @Test fun `확정 · 출금 완료는 확정 청구액`() {
        assertEquals(87_000L, bill("CONFIRMED", final = 87_000).amount)
        assertEquals(87_000L, bill("PAID", final = 87_000).amount)
        assertNull(bill("PAID", final = 87_000).amountNote())
    }

    @Test fun `미납은 남은 미납액을 크게 · 원래 청구액은 작게`() {
        val b = bill("UNPAID", final = 42_000, unpaid = 30_000)
        assertEquals(30_000L, b.amount)
        assertEquals(42_000L, b.originalAmount)
        assertEquals("청구 42,000원", b.amountNote())
    }

    @Test fun `미납 전액이면 원래 청구액 줄은 없다`() {
        assertNull(bill("UNPAID", final = 42_000, unpaid = 42_000).originalAmount)
    }

    @Test fun `CANCELED(미납이었다가 취소로 0원)는 0원 · 정산 끝`() {
        val b = bill("CANCELED", final = 12_000, unpaid = 0)
        assertEquals(0L, b.amount)
        assertEquals(BillState.CANCELED, b.state)
        assertEquals("정산 끝", b.state.label)
        assertEquals("청구 12,000원", b.amountNote())
    }

    @Test fun `금액 칸이 비어 와도 다른 서버 값으로 — 빼고 더하지 않는다`() {
        assertEquals(50_000L, bill("ACCUMULATING", expected = null).amount)
        assertEquals(50_000L, bill("PAID", final = null).amount)
    }

    // ── 잘못된 값 · 모르는 값 ──

    @Test fun `모르는 상태 값은 '확인 중' — 응답 전체가 실패하지 않는다`() {
        val b = bill("REFUNDING_SOMEHOW", final = 10_000)
        assertEquals(BillState.UNKNOWN, b.state)
        assertEquals("확인 중", b.state.label)
        assertEquals(10_000L, b.amount)
    }

    @Test fun `상태가 소문자 · null이어도 읽는다`() {
        assertEquals(BillState.PAID, billStateOf("paid"))
        assertEquals(BillState.UNKNOWN, billStateOf(null))
        assertEquals(RefundState.CANCELED, refundStateOf(" canceled "))
        assertEquals(RefundState.UNKNOWN, refundStateOf("NEW_STATE"))
    }

    @Test fun `번호 없는 청구서 줄은 뺀다 · 이름이 없으면 '신용카드'`() {
        val list = BillListDto(items = listOf(BillDto(billId = null), BillDto(billId = 3, nickname = " "))).toBillList()
        assertEquals(listOf(3L), list.bills.map { it.billId })
        assertEquals("신용카드", list.bills.single().cardName)
    }

    @Test fun `날짜 · 월 · 시각을 못 읽으면 그 칸만 비운다`() {
        val b = BillDto(billId = 1, billingMonth = "11월", paymentDate = "2026/11/14", paidAt = "어제").toBill()!!
        assertNull(b.billingMonth)
        assertNull(b.paymentDate)
        assertNull(b.paidAt)
    }

    @Test fun `시각은 UTC에 Z — 한국 날짜로 바꾼다 · Z가 없어도 UTC로`() {
        assertEquals(LocalDate.of(2026, 11, 16), parseInstant("2026-11-15T22:00:12Z")?.toLocalDate())
        assertEquals(LocalDate.of(2026, 11, 16), parseInstant("2026-11-15T22:00:12")?.toLocalDate())
    }

    @Test fun `가린 번호에서 끝 4자리 — 다 가렸으면 null`() {
        assertEquals("1021", lastFour("****-****-****-1021"))
        assertEquals("1234", lastFour("0012-***-1234"))
        assertNull(lastFour("****-****-****-****"))
        assertNull(lastFour(null))
    }

    @Test fun `다음 출금의 날짜 · 금액이 비면 요약에서 뺀다`() {
        val list = BillListDto(summary = BillSummaryDto(BillNextWithdrawalDto(1, null, 5_000), unpaidCount = 2)).toBillList()
        assertNull(list.next)
        assertEquals(2, list.unpaidCount)
    }

    // ── 문장 ──

    @Test fun `휴일이라 밀린 출금일`() {
        assertTrue(bill("CONFIRMED", final = 1, payment = "2026-11-14", withdrawal = "2026-11-16").withdrawalShifted)
        assertFalse(bill("CONFIRMED", final = 1).withdrawalShifted)
    }

    @Test fun `상태별 목록 줄 · 상세 문장`() {
        assertEquals("이용 10/1~10/31", bill("ACCUMULATING", expected = 1).rowLine())
        assertEquals("11/16 출금 못 함", bill("UNPAID", final = 1, unpaid = 1, withdrawal = "2026-11-16").rowLine())
        assertEquals("11월 16일(월)에 출금해요", bill("CONFIRMED", final = 1, withdrawal = "2026-11-16").statusLine())
        assertEquals("출금하지 못했어요 · 다시 출금하지 않아요", bill("UNPAID", final = 1, unpaid = 1).statusLine())
        assertNull(bill("WHAT", final = 1).statusLine())
    }

    @Test fun `월 머리 — 올해면 월만`() {
        val today = LocalDate.of(2026, 11, 8)
        assertEquals("12월 청구", billingMonthLabel(YearMonth.of(2026, 12), today))
        assertEquals("2025년 12월 청구", billingMonthLabel(YearMonth.of(2025, 12), today))
        assertEquals("청구 월 미확인", billingMonthLabel(null, today))
    }

    // ── 상세 ──

    @Test fun `진행 중 청구서의 청구할인은 예상(확정 시각 없음)`() {
        val d = BillDetailDto(
            billId = 9, status = "ACCUMULATING",
            discounts = listOf(BillDiscountDto(1, "카페 할인", 500, confirmedAt = null)),
        ).toBillDetail(9)
        assertTrue(d.discountsEstimated)
    }

    @Test fun `상세 응답에 번호가 빠지면 연 번호를 쓴다 · 회차 수는 내역 길이`() {
        val d = BillDetailDto(items = listOf(BillUsageDto(billItemId = 1), BillUsageDto(billItemId = 2, merchantName = ""))).toBillDetail(42)
        assertEquals(42L, d.bill.billId)
        assertEquals(2, d.bill.itemCount)
        assertEquals("결제", d.usages[1].merchant)
    }

    @Test fun `출금 실패 시각은 paidAt이 아니다`() {
        val d = BillDetailDto(billId = 1, status = "UNPAID", withdrawal = BillWithdrawalDto("2026-11-13T22:00:00Z", null)).toBillDetail(1)
        assertNull(d.bill.paidAt)
        assertEquals(LocalDate.of(2026, 11, 14), d.withdrawalAttemptedAt?.toLocalDate())
    }

    @Test fun `환급 줄에 회차의 가맹점 이름을 붙인다 · 없는 회차면 null`() {
        val d = BillDetailDto(
            billId = 1,
            items = listOf(BillUsageDto(billItemId = 7, merchantName = "쿠팡")),
            refunds = listOf(BillRefundDto(1, 7, 12_000, "PENDING"), BillRefundDto(2, 99, 1_000, "FAILED")),
        ).toBillDetail(1)
        assertEquals(listOf("쿠팡", null), d.refunds.map { it.merchant })
    }

    @Test fun `청구서 상세의 무효 환급은 0원 — 환급 내역과 같게(BE 상세는 원래 금액을 준다)`() {
        val d = BillDetailDto(
            billId = 1,
            refunds = listOf(BillRefundDto(1, 7, 12_000, "CANCELED"), BillRefundDto(2, 8, 5_000, "PENDING")),
        ).toBillDetail(1)
        assertEquals(listOf(0L, 5_000L), d.refunds.map { it.amount })
    }

    @Test fun `회차 상태 태그 — 청구 제외 · 환급 대기 · 환급 완료만`() {
        assertEquals("청구 제외", usageStateOf("EXCLUDED").tag)
        assertEquals("환급 완료", usageStateOf("REFUNDED").tag)
        assertNull(usageStateOf("BILLED").tag)
        assertNull(usageStateOf("SOMETHING").tag)
    }

    private fun detailWith(status: String, vararg usage: String?) =
        BillDetailDto(billId = 1, status = status, items = usage.mapIndexed { i, s -> BillUsageDto(billItemId = i + 1L, status = s) }).toBillDetail(1)

    @Test fun `확정된 청구서에 청구 제외가 있으면 이용 금액 안내 한 줄`() {
        // 미납 제외 · 0원 닫기는 확정 뒤 EXCLUDED라 이용 금액에 남는다 — 응답으로는 확정 전 취소와 가를 수 없다
        listOf("CONFIRMED", "PAID", "UNPAID", "CANCELED", "SOMETHING").forEach { status ->
            assertEquals(status, "청구 제외 가운데 청구가 확정된 뒤 취소한 건은 이용 금액에 남아 있어요", detailWith(status, "BILLED", "EXCLUDED").excludedNote())
        }
    }

    @Test fun `쌓이는 중인 청구서의 청구 제외 · 청구 제외가 없는 청구서는 안내를 달지 않는다`() {
        assertNull(detailWith("ACCUMULATING", "PENDING", "EXCLUDED").excludedNote())   // 확정 전 취소뿐
        assertNull(detailWith("UNPAID", "BILLED", "REFUND_PENDING").excludedNote())
        assertNull(detailWith("PAID").excludedNote())
    }

    // ── 환급 ──

    private fun refund(
        status: String,
        estimated: Boolean = false,
        reason: String = "CANCEL_AFTER_CONFIRM",
        refundId: Long? = 1,
        due: String? = null,
        after: String? = null,
        completed: String? = null,
    ) = RefundDto(
        refundId = refundId, paymentId = 50, billId = 9, nickname = "톡톡", merchantName = "쿠팡", reason = reason,
        amount = 12_000, estimated = estimated, status = status, dueDate = due, scheduledAfter = after, completedAt = completed,
    ).toRefund()!!

    @Test fun `출금 전 청구서의 환급은 예상 · 출금 뒤 정해진다`() {
        val r = refund("PENDING", estimated = true, after = "2026-12-14")
        assertEquals("예상", r.tag())
        assertEquals("톡톡 · 12/14 출금 뒤 금액이 정해져요", r.line())
    }

    @Test fun `기한이 정해진 환급은 그날까지 입금`() {
        val r = refund("PENDING", due = "2026-12-17")
        assertEquals("입금 예정", r.tag())
        assertEquals("톡톡 · 12/17까지 입금", r.line())
    }

    @Test fun `체크카드 취소는 환불 완료 · 환급 번호 대신 결제 번호로 가린다`() {
        val r = refund("COMPLETED", reason = "CHECK_CANCEL", refundId = null, completed = "2026-10-18T02:20:42Z")
        assertEquals("p50", r.key)
        assertEquals("환불 완료", r.tag())
        assertEquals("톡톡 · 10/18 환불", r.line())
    }

    @Test fun `계좌가 없어 실패한 환급 · 무효 환급`() {
        assertEquals("톡톡 · 환급 계좌가 없어 입금하지 못했어요", refund("FAILED").line())
        assertEquals("무효", refund("CANCELED").tag())
        assertEquals("톡톡 · 미납으로 끝나 돌려줄 돈이 없어요", refund("CANCELED").line())
        assertEquals("확인 중", refund("SOMETHING").tag())
    }

    @Test fun `환급 번호도 결제 번호도 없는 줄은 뺀다`() {
        val page = RefundPageDto(items = listOf(RefundDto(refundId = null, paymentId = null), RefundDto(refundId = 3))).toRefundPage(1)
        assertEquals(listOf("r3"), page.items.map { it.key })
    }
}
