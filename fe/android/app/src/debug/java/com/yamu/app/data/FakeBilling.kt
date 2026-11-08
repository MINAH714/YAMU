package com.yamu.app.data

import com.yamu.app.core.format.KST
import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.feature.billing.data.AccountRefDto
import com.yamu.app.feature.billing.data.BillDetail
import com.yamu.app.feature.billing.data.BillDetailDto
import com.yamu.app.feature.billing.data.BillDiscountDto
import com.yamu.app.feature.billing.data.BillDto
import com.yamu.app.feature.billing.data.BillAmountsDto
import com.yamu.app.feature.billing.data.BillList
import com.yamu.app.feature.billing.data.BillListDto
import com.yamu.app.feature.billing.data.BillNextWithdrawalDto
import com.yamu.app.feature.billing.data.BillRefundDto
import com.yamu.app.feature.billing.data.BillSummaryDto
import com.yamu.app.feature.billing.data.BillUsageDto
import com.yamu.app.feature.billing.data.BillWithdrawalDto
import com.yamu.app.feature.billing.data.BillingRepository
import com.yamu.app.feature.billing.data.RefundDto
import com.yamu.app.feature.billing.data.RefundPage
import com.yamu.app.feature.billing.data.RefundPageDto
import com.yamu.app.feature.billing.data.RefundSummaryDto
import com.yamu.app.feature.billing.data.UsagePeriodDto
import com.yamu.app.feature.billing.data.toBillDetail
import com.yamu.app.feature.billing.data.toBillList
import com.yamu.app.feature.billing.data.toRefundPage
import kotlinx.coroutines.delay
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton

/**
 * debug 전용 청구 · 환급 — 서버 응답(DTO)을 만들어 진짜와 같은 바꾸기를 거친다. 카드 번호는 FakeBank(11 ~ 17)와 같다.
 * LOCA(13)의 확정 청구서는 카드 상세의 '다음 출금'(오늘 + 10일 · 320,000원)과 같은 값이다.
 * 없는 청구서 번호는 404(BE는 남의 청구서도 같은 404). 환급은 한 페이지 4건이라 '더 보기'까지 그려 볼 수 있다.
 * 모르는 카드 번호는 청구서 없음 — link만 진짜(cashflow · forecast가 켬)일 때 진짜 카드 번호로 와도 '찾을 수 없는 카드'가 뜨지 않게
 */
@Singleton
class FakeBillingRepository @Inject constructor() : BillingRepository {

    override suspend fun bills(userCardId: Long?): ApiResult<BillList> =
        withFault(empty = { ApiResult.Success(BillListDto().toBillList()) }) {
            delay(400)
            when {
                userCardId == null -> ApiResult.Success(billList(bills()))
                else -> ApiResult.Success(billList(bills().filter { it.card == userCardId }))   // 체크카드 · 모르는 카드는 빈 목록
            }
        }

    override suspend fun bill(billId: Long): ApiResult<BillDetail> = withFault {
        delay(400)
        bills().firstOrNull { it.id == billId }?.let { ApiResult.Success(it.detail().toBillDetail(billId)) }
            ?: ApiResult.Failure(ApiError.NotFound("RESOURCE_NOT_FOUND"))
    }

    override suspend fun refunds(page: Int): ApiResult<RefundPage> =
        withFault(empty = { ApiResult.Success(RefundPageDto().toRefundPage(page)) }) {
            delay(400)
            val all = refunds()
            val pending = all.filter { it.status == "PENDING" }
            val rows = all.drop((page - 1) * PAGE_SIZE).take(PAGE_SIZE)
            ApiResult.Success(
                RefundPageDto(
                    summary = RefundSummaryDto(
                        pendingAmount = pending.sumOf { it.amount },
                        pendingCount = pending.size.toLong(),
                        estimatedAmount = pending.filter { it.estimated }.sumOf { it.amount },
                        estimatedCount = pending.count { it.estimated }.toLong(),
                    ),
                    items = rows,
                    page = page,
                    hasNext = page * PAGE_SIZE < all.size,
                ).toRefundPage(page),
            )
        }

    // ───────────────────────── 가짜 값 ─────────────────────────

    private class FakeCard(val name: String, val bank: String, val accountId: Long, val accountLast4: String)

    private class FakeBill(
        val id: Long,
        val card: Long,
        val status: String,
        val payment: LocalDate,
        val withdrawal: LocalDate = payment.businessDay(),
        val items: List<BillUsageDto>,
        val discount: Long = 0,
        val unpaid: Long? = null,
        val refunds: List<BillRefundDto> = emptyList(),
    ) {
        private val c = CARDS.getValue(card)
        private val closing = payment.minusDays(14)
        private val itemAmount = items.filter { it.status != "EXCLUDED" }.sumOf { it.amount }
        private val accumulating = status == "ACCUMULATING"
        private val account = AccountRefDto(c.accountId, c.bank, "****-****-****-${c.accountLast4}")
        private val period = UsagePeriodDto(payment.minusMonths(1).minusDays(14).plusDays(1).toString(), closing.toString())   // 전월 마감 다음 날 ~ 이번 마감일
        val expected: Long? = if (accumulating) itemAmount - discount else null
        val final: Long? = if (accumulating) null else itemAmount - discount

        fun row() = BillDto(
            billId = id, userCardId = card, nickname = c.name, billingMonth = YearMonth.from(payment).toString(), usagePeriod = period,
            closingDate = closing.toString(), paymentDate = payment.toString(), withdrawalDate = withdrawal.toString(), status = status,
            itemCount = items.count { it.status != "EXCLUDED" }, itemAmount = itemAmount, billingDiscount = discount,
            expectedAmount = expected, finalAmount = final, unpaidAmount = unpaid,
            paidAt = if (status == "PAID") utc(withdrawal, 7) else null, withdrawalAccount = account,
        )

        fun detail() = BillDetailDto(
            billId = id, userCardId = card, nickname = c.name, maskedNumber = "****-****-****-${LAST4.getValue(card)}",
            billingMonth = YearMonth.from(payment).toString(), usagePeriod = period, closingDate = closing.toString(),
            paymentDate = payment.toString(), withdrawalDate = withdrawal.toString(), status = status,
            amounts = BillAmountsDto(itemAmount, discount, expected, final, unpaid), withdrawalAccount = account,
            items = items,
            discounts = if (discount > 0) listOf(BillDiscountDto(1, "${c.name} 청구할인", discount, if (accumulating) null else utc(closing.plusDays(1), 0, 30))) else emptyList(),
            withdrawal = when (status) {
                "PAID" -> BillWithdrawalDto(utc(withdrawal, 7), "SUCCESS")
                "UNPAID", "CANCELED" -> BillWithdrawalDto(utc(withdrawal, 7), null)
                else -> null
            },
            refunds = refunds,
        )
    }

    private fun billList(rows: List<FakeBill>): BillList {
        val open = rows.filter { it.status == "ACCUMULATING" || it.status == "CONFIRMED" }.minByOrNull { it.withdrawal }
        return BillListDto(
            items = rows.sortedWith(compareByDescending<FakeBill> { YearMonth.from(it.payment) }.thenByDescending { it.id }).map { it.row() },
            summary = BillSummaryDto(
                nextWithdrawal = open?.let { BillNextWithdrawalDto(it.id, it.withdrawal.toString(), it.expected ?: it.final) },
                unpaidCount = rows.count { it.status == "UNPAID" },
            ),
        ).toBillList()
    }

    /** 날짜는 오늘 기준으로 다시 만든다 — 언제 켜도 진행 중 · 확정 · 미납 · 출금 완료가 다 보이게 */
    private fun bills(): List<FakeBill> {
        val today = LocalDate.now(KST)
        val lastPay = if (today.dayOfMonth > 14) today.withDayOfMonth(14) else today.minusMonths(1).withDayOfMonth(14)
        val nextPay = YearMonth.from(today).plusMonths(1).atDay(14).let { if (it.minusDays(14) < today) it.plusMonths(1) else it }
        val locaPay = today.plusDays(10)
        return listOf(
            FakeBill(
                201, card = 13, status = "CONFIRMED", payment = locaPay, withdrawal = locaPay, discount = 6_000,
                items = listOf(
                    usage(7101, 9101, locaPay.minusDays(20), "쿠팡", 12_000, "REFUND_PENDING"),
                    usage(7102, 9102, locaPay.minusDays(24), "롯데백화점 본점", 214_000),
                    usage(7103, 9103, locaPay.minusDays(30), "롯데마트 잠실점", 100_000),
                ),
                refunds = listOf(BillRefundDto(301, 7101, 12_000, "PENDING")),
            ),
            FakeBill(
                202, card = 11, status = "ACCUMULATING", payment = nextPay, discount = 5_500,
                items = listOf(
                    usage(7201, 9201, today.minusDays(1), "스타벅스 역삼점", 15_000),
                    usage(7202, 9202, today.minusDays(3), "GS25 역삼점", 4_500, "EXCLUDED"),
                    usage(7203, 9203, today.minusDays(4), "올리브영 강남점", 47_000),
                    usage(7204, 9204, today.minusDays(40), "롯데하이마트 강남점", 120_000, plan = "INSTALLMENT", seq = 2, totalSeq = 3),
                ),
            ),
            FakeBill(
                203, card = 13, status = "UNPAID", payment = lastPay, unpaid = 30_000,
                items = listOf(
                    usage(7301, 9301, lastPay.minusDays(20), "무신사", 12_000),
                    usage(7302, 9302, lastPay.minusDays(25), "이마트 성수점", 30_000),
                ),
                refunds = listOf(BillRefundDto(304, 7301, 12_000, "CANCELED")),   // BE 청구서 상세는 무효도 원래 금액을 준다(환급 내역은 0원)
            ),
            FakeBill(
                204, card = 11, status = "PAID", payment = lastPay, discount = 3_000,
                items = listOf(
                    usage(7401, 9401, lastPay.minusDays(18), "스타벅스 역삼점", 30_000, "REFUND_PENDING"),
                    usage(7402, 9402, lastPay.minusDays(22), "배달의민족", 25_000),
                    usage(7403, 9403, lastPay.minusDays(27), "교보문고 광화문점", 35_000),
                ),
                refunds = listOf(BillRefundDto(302, 7401, 30_000, "PENDING")),
            ),
            FakeBill(
                205, card = 12, status = "CANCELED", payment = lastPay, unpaid = 0,
                items = listOf(usage(7501, 9501, lastPay.minusDays(19), "쿠팡이츠", 12_000)),
            ),
            FakeBill(
                206, card = 11, status = "PAID", payment = lastPay.minusMonths(1),
                items = listOf(
                    usage(7601, 9601, lastPay.minusMonths(1).minusDays(17), "다이소 역삼점", 8_000, "REFUNDED"),
                    usage(7602, 9602, lastPay.minusMonths(1).minusDays(21), "이마트 역삼점", 23_000, "REFUND_PENDING"),
                    usage(7603, 9603, lastPay.minusMonths(1).minusDays(26), "CGV 강남", 33_300),
                ),
                refunds = listOf(BillRefundDto(305, 7601, 8_000, "COMPLETED"), BillRefundDto(306, 7602, 23_000, "FAILED")),
            ),
        )
    }

    /** 생성 최신순(BE와 같은 순서) */
    private fun refunds(): List<RefundDto> {
        val today = LocalDate.now(KST)
        val b = bills().associateBy { it.id }
        return listOf(
            RefundDto(301, 9101, 201, CARDS.getValue(13).name, "쿠팡", "CANCEL_AFTER_CONFIRM", 12_000, estimated = true, status = "PENDING",
                createdAt = utc(today.minusDays(1), 10, 20), scheduledAfter = b.getValue(201).withdrawal.toString()),
            RefundDto(302, 9401, 204, CARDS.getValue(11).name, "스타벅스 역삼점", "CANCEL_AFTER_CONFIRM", 30_000, status = "PENDING",
                createdAt = utc(today.minusDays(3), 14, 5), dueDate = today.plusDays(2).toString()),
            RefundDto(null, 9701, null, "KB국민 노리2 체크카드(KB Pay)", "올리브영 강남점", "CHECK_CANCEL", 14_250, status = "COMPLETED",
                createdAt = utc(today.minusDays(5), 11, 20), dueDate = today.minusDays(5).toString(), completedAt = utc(today.minusDays(5), 11, 20)),
            RefundDto(304, 9301, 203, CARDS.getValue(13).name, "무신사", "CANCEL_AFTER_CONFIRM", 0, status = "CANCELED",
                createdAt = utc(today.minusDays(12), 9, 0)),
            RefundDto(305, 9601, 206, CARDS.getValue(11).name, "다이소 역삼점", "CANCEL_AFTER_CONFIRM", 8_000, status = "COMPLETED",
                createdAt = utc(today.minusDays(40), 18, 0), dueDate = today.minusDays(35).toString(), completedAt = utc(today.minusDays(36), 9, 0)),
            RefundDto(306, 9602, 206, CARDS.getValue(11).name, "이마트 역삼점", "CANCEL_AFTER_CONFIRM", 23_000, status = "FAILED",
                createdAt = utc(today.minusDays(41), 12, 30), dueDate = today.minusDays(35).toString()),
        )
    }

    private companion object {
        const val PAGE_SIZE = 4

        val CARDS = mapOf(
            11L to FakeCard("KB국민 청춘대로 톡톡카드", "KB국민은행", 1, "1234"),
            12L to FakeCard("현대카드ZERO Edition3(포인트형)", "KB국민은행", 1, "1234"),
            13L to FakeCard("LOCA LIKIT Shop", "KB국민은행", 1, "1234"),
            14L to FakeCard("삼성 iD ON 카드", "KB국민은행", 1, "1234"),
            15L to FakeCard("신한카드 Mr.Life", "신한은행", 2, "5678"),
        )
        val LAST4 = mapOf(11L to "1021", 12L to "3310", 13L to "7782", 14L to "4410", 15L to "5096")

        fun usage(
            billItemId: Long, paymentId: Long, on: LocalDate, merchant: String, amount: Long, status: String = "BILLED",
            plan: String = "LUMP_SUM", seq: Int = 1, totalSeq: Int = 1,
        ) = BillUsageDto(billItemId, paymentId, utc(on, 12, 30), merchant, plan, seq, totalSeq, amount, status)

        /** 한국 시각 → BE 응답 모양(UTC에 Z) */
        fun utc(date: LocalDate, hour: Int, minute: Int = 0): String =
            date.atTime(hour, minute).atZone(KST).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime().toString() + "Z"

        /** 결제일이 주말이면 다음 월요일(공휴일은 빼고 단순하게) */
        fun LocalDate.businessDay(): LocalDate = when (dayOfWeek) {
            DayOfWeek.SATURDAY -> plusDays(2)
            DayOfWeek.SUNDAY -> plusDays(1)
            else -> this
        }
    }
}
