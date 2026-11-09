package com.yamu.app.data

import com.yamu.app.core.format.KST
import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.session.DevSession
import com.yamu.app.core.session.HomeScenario
import com.yamu.app.core.session.PayScenario
import com.yamu.app.feature.link.data.brandOf
import com.yamu.app.feature.payment.data.ApproveOutcome
import com.yamu.app.feature.payment.data.CancelResult
import com.yamu.app.feature.payment.data.CodeState
import com.yamu.app.feature.payment.data.MemoRules
import com.yamu.app.feature.payment.data.Merchant
import com.yamu.app.feature.payment.data.PayStatus
import com.yamu.app.feature.payment.data.PaymentEdits
import com.yamu.app.feature.payment.data.PayCard
import com.yamu.app.feature.payment.data.PayCode
import com.yamu.app.feature.payment.data.PayCodeStatus
import com.yamu.app.feature.payment.data.PaymentReceipt
import com.yamu.app.feature.payment.data.PaymentRepository
import com.yamu.app.feature.payment.data.PaymentRequest
import com.yamu.app.feature.payment.data.ReceiptBenefit
import com.yamu.app.feature.payment.data.declineMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/** 가짜 가맹점 — 고객 앱 P-01과 모의 POS 단말(debug)이 같이 쓴다 */
object FakeMerchants {
    val all = listOf(
        // 카드 혜택이 브랜드에 걸린 곳(톡톡 스타벅스 · 노리2 GS25)은 그 브랜드 이름으로
        Merchant(301, "스타벅스 역삼점", "카페", isOnline = false, posCode = "POS-0301"),
        Merchant(302, "야무마트 강남점", "대형마트", isOnline = false, posCode = "POS-0302"),
        Merchant(303, "GS25 역삼역점", "편의점", isOnline = false, posCode = "POS-0303"),
        Merchant(304, "야무주유소 선릉점", "주유", isOnline = false, posCode = "POS-0304"),
        Merchant(305, "야무텔레콤 강남점", "통신", isOnline = false, posCode = "POS-0305"),
        Merchant(318, "야무몰", "온라인 쇼핑", isOnline = true, posCode = null),
    )
}

/**
 * debug 전용 가짜 결제 서버 — 코드 발급 → (디버그 메뉴 '결제 상태'대로) 3초 뒤 가짜 가맹점이 찍음 → 승인 · 거절.
 * 카드 · 혜택은 BE 카드 시드(be/seed 01 · 02)에 있는 상품의 실제 혜택 · 월 한도로 계산한다.
 * 실적 숫자(이번 달 285,000 / 300,000원)는 프로토타입 P-07 그대로.
 */
@Singleton
class FakePaymentRepository @Inject constructor(
    private val perf: FakePerformanceStore,
    private val edits: PaymentEdits,
) : PaymentRepository {

    private class Issued(val code: PayCode, val issuedAt: Long, val scenario: PayScenario) {
        var request: PaymentRequest? = null
        var closed = false
        var declines = 0
    }

    private val codes = mutableMapOf<String, Issued>()
    private val receipts = mutableMapOf<Long, PaymentReceipt>()
    private var nextPaymentId = 1001L

    private fun card(id: Long, issuer: String, color: String, name: String, last4: String, check: Boolean, benefit: Long, benefitName: String?, months: List<Int> = emptyList(), charged: Long? = null, perf: String? = null) =
        PayCard(id, brandOf(issuer, color = color), name, last4, check, benefit.toBigDecimal(), benefitName, charged, months, benefitName?.let { "$it ${"%,d".format(benefit)}원" }, perf)

    // 혜택 · 월 한도는 시드 값 — 톡톡 스타벅스 50%(한도 10,000) · ZERO 1.2% M포인트 · Mr.Life 주말 마트 10%(한도 3,000) · 노리2 GS25·CU 5%(한도 2,000)
    private val toktok get() = card(11, "KB국민카드", "#FFBC00", "KB국민 청춘대로 톡톡카드", "1021", false, 7_500, "스타벅스 50% 청구할인", months = listOf(2, 3, 6), perf = "이번 달 실적 +15,000원")
    private val zero get() = card(12, "현대카드", "#1A1A1A", "현대카드ZERO Edition3(포인트형)", "3310", false, 180, "1.2% M포인트 적립", perf = "이번 달 실적 +15,000원")
    private fun mrLife(benefit: Long) = card(15, "신한카드", "#0046FF", "신한카드 Mr.Life", "5096", false, benefit, "주말 마트 10% 할인", months = listOf(2, 3, 6))
    private fun nori2(benefit: Long, amount: Long) = card(16, "KB국민카드", "#60584C", "KB국민 노리2 체크카드(KB Pay)", "8823", true, benefit, "GS25·CU 5% 할인", charged = amount - benefit)

    private fun requestFor(scenario: PayScenario, paymentId: Long, deadline: String): PaymentRequest = when (scenario) {
        PayScenario.MART -> PaymentRequest(
            paymentId, "야무마트 강남점", "대형마트", 90_000, deadline,
            listOf(
                mrLife(3_000).copy(performanceNote = "이번 달 실적 +90,000원"),
                zero.copy(netBenefit = 1_080.toBigDecimal(), reason = "1.2% M포인트 적립 1,080원", installmentOptions = listOf(2, 3, 6), performanceNote = "이번 달 실적 +90,000원"),
            ),
        )
        PayScenario.CHECK_DECLINED -> PaymentRequest(
            paymentId, "GS25 역삼역점", "편의점", 15_000, deadline,
            listOf(nori2(750, 15_000).copy(performanceNote = "할인받은 결제는 실적에서 빠져요"), zero),
        )
        PayScenario.NO_CARD -> PaymentRequest(paymentId, "스타벅스 역삼점", "카페", 15_000, deadline, emptyList())
        // 데이터 부족(DATA_INSUFFICIENT) — 1순위 없이 비교 못 한 카드만. 하나는 혜택 없이 바로 결제, 하나는 결제 가능 여부도 모름
        PayScenario.CARDS_UNKNOWN -> PaymentRequest(
            paymentId, "스타벅스 역삼점", "카페", 15_000, deadline,
            listOf(uncompared(zero, direct = true), uncompared(nori2(0, 15_000), direct = false)),
        )
        else -> PaymentRequest(paymentId, "스타벅스 역삼점", "카페", 15_000, deadline, listOf(toktok, zero))
    }

    /** BE uncomparedCards 한 장과 같은 모양 — 문구는 BE UncomparedReason 기본 문구 그대로 */
    private fun uncompared(c: PayCard, direct: Boolean) = c.copy(
        netBenefit = 0.toBigDecimal(), benefitName = null, chargedAmount = null, installmentOptions = emptyList(), performanceNote = null,
        reason = if (direct) "혜택 정보를 확인하지 못해 비교에서 제외했어요." else "결제 가능 여부를 확인하지 못해 비교에서 제외했어요.",
        benefitUnknown = true, selectable = direct,
    )

    override suspend fun issueCode(stepUpToken: String): ApiResult<PayCode> {
        delay(400)
        val scenario = DevSession.payScenario
        val token = "YAMU" + List(12) { "0123456789ABCDEF"[Random.nextInt(16)] }.joinToString("")
        val life = if (scenario == PayScenario.NO_SCAN) 20L else 180L
        val expires = OffsetDateTime.now(KST).plusSeconds(life).toString()
        val code = PayCode(token, token, "yamu://pay/$token", expires)
        codes[token] = Issued(code, System.currentTimeMillis(), scenario)
        return ApiResult.Success(code)
    }

    override suspend fun codeStatus(token: String): ApiResult<PayCodeStatus> {
        delay(150)
        val issued = codes[token] ?: return ApiResult.Failure(ApiError.NotFound("BARCODE_NOT_FOUND"))
        val now = System.currentTimeMillis()
        if (issued.closed) return ApiResult.Success(PayCodeStatus(CodeState.USED, null))
        issued.request?.let { return ApiResult.Success(PayCodeStatus(CodeState.SCANNED, it)) }
        if (OffsetDateTime.parse(issued.code.expiresAtIso).toInstant().toEpochMilli() < now) {
            return ApiResult.Success(PayCodeStatus(CodeState.EXPIRED, null))
        }
        if (issued.scenario != PayScenario.NO_SCAN && now - issued.issuedAt >= 3_000) {
            val deadline = OffsetDateTime.now(KST).plusSeconds(if (issued.scenario == PayScenario.REQUEST_SHORT) 15 else 60).toString()
            issued.request = requestFor(issued.scenario, nextPaymentId++, deadline)
            return ApiResult.Success(PayCodeStatus(CodeState.SCANNED, issued.request))
        }
        return ApiResult.Success(PayCodeStatus(CodeState.ISSUED, null))
    }

    override suspend fun approve(request: PaymentRequest, card: PayCard, installmentMonths: Int?, idempotencyKey: String): ApiResult<ApproveOutcome> {
        val slow = codes.values.firstOrNull { it.request?.paymentId == request.paymentId }?.scenario == PayScenario.APPROVE_SLOW
        delay(if (slow) 4_000 else 900)
        receipts[request.paymentId]?.let { return ApiResult.Success(ApproveOutcome.Approved(it)) }   // 같은 결제 두 번 — 처음 결과
        val issued = codes.values.firstOrNull { it.request?.paymentId == request.paymentId }
            ?: return ApiResult.Failure(ApiError.NotFound("RESOURCE_NOT_FOUND"))
        val deadline = request.confirmDeadlineIso?.let { OffsetDateTime.parse(it).toInstant().toEpochMilli() }
        if (deadline != null && deadline < System.currentTimeMillis()) return ApiResult.Failure(ApiError.Unknown("PAYMENT_EXPIRED", 410))
        if (issued.scenario == PayScenario.CHECK_DECLINED && card.isCheck) {
            issued.declines++
            return ApiResult.Success(
                ApproveOutcome.Declined(declineMessage("INSUFFICIENT_BALANCE"), retryable = true, remainingAttempts = 3 - issued.declines, suggestedCardId = request.cards.firstOrNull { !it.isCheck }?.userCardId),
            )
        }
        issued.closed = true
        perf.addPayment(card.userCardId, request.amount, counted = !card.isCheck)   // 가짜 실적 장부에 반영 → 실적관리 값 · 판정이 바뀐다
        val perfCard = perf.card(card.userCardId)
        val now = OffsetDateTime.now(KST)
        val payDay = YearMonth.now(KST).plusMonths(1).atDay(14)
        val receipt = PaymentReceipt(
            paymentId = request.paymentId,
            merchantName = request.merchantName,
            categoryName = request.categoryName,
            amount = request.amount,
            paidAtIso = now.truncatedTo(ChronoUnit.SECONDS).toString(),
            cardId = card.userCardId,
            cardBrand = card.brand,
            cardName = card.name,
            cardLast4 = card.last4,
            isCheck = card.isCheck,
            approvalNo = "Y" + now.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + "%03d".format(request.paymentId % 1000),
            installmentMonths = installmentMonths,
            benefits = listOfNotNull(card.benefitName?.let { ReceiptBenefit(it, card.netBenefit.toLong()) }),
            chargedAmount = card.chargedAmount ?: request.amount,
            balanceAfter = if (card.isCheck) 950_000 - (card.chargedAmount ?: request.amount) else null,
            billDateIso = if (card.isCheck) null else payDay.toString(),
            billAmount = if (card.isCheck) null else (request.amount - card.netBenefit.toLong()) / (installmentMonths ?: 1),
            perfMonth = LocalDate.now(KST).monthValue,
            perfRecognized = if (card.isCheck) 0 else request.amount,
            perfCounted = !card.isCheck,
            perfTotalAfter = perfCard?.recognized,
            perfTarget = perfCard?.target,
        )
        receipts[request.paymentId] = receipt
        DevSession.homeScenario = HomeScenario.AFTER_PAYMENT    // 가짜 홈: '결제 반영 직후'
        return ApiResult.Success(ApproveOutcome.Approved(receipt))
    }

    override suspend fun reject(paymentId: Long, idempotencyKey: String): ApiResult<Unit> {
        delay(400)
        codes.values.firstOrNull { it.request?.paymentId == paymentId }?.closed = true
        return ApiResult.Success(Unit)
    }

    override fun recentReceipt(paymentId: Long): PaymentReceipt? = receipts[paymentId]

    /** 이번 실행에서 승인한 가짜 결제 — 가짜 결제 내역 · 소비분석 맨 위에 */
    fun approved(): List<PaymentReceipt> = receipts.values.sortedByDescending { it.paidAtIso }

    override suspend fun detail(paymentId: Long): ApiResult<PaymentReceipt> = withFault { detailNow(paymentId) }

    private suspend fun detailNow(paymentId: Long): ApiResult<PaymentReceipt> {
        delay(400)
        return ApiResult.Success(receiptOf(paymentId).withEdits())
    }

    /** 이번 실행의 결제 → 가짜 결제 내역 → 그 밖의 번호는 예전 스타벅스 결제(홈 최근 결제 · 알림에서 들어오는 번호) */
    fun receiptOf(paymentId: Long): PaymentReceipt = receipts[paymentId] ?: FakePaymentHistory.receipt(paymentId) ?: PaymentReceipt(
        paymentId = paymentId, merchantName = "스타벅스 역삼점", categoryName = "카페", amount = 15_000,
        paidAtIso = OffsetDateTime.now(KST).minusHours(1).truncatedTo(ChronoUnit.MINUTES).toString(),
        cardId = 11, cardBrand = toktok.brand, cardName = toktok.name, cardLast4 = "1021", isCheck = false,
        approvalNo = "Y20261026093800938", installmentMonths = null, benefits = listOf(ReceiptBenefit("스타벅스 50% 청구할인", 7_500)),
        chargedAmount = 15_000, balanceAfter = null, billDateIso = YearMonth.now(KST).plusMonths(1).atDay(14).toString(), billAmount = 7_500,
        perfMonth = LocalDate.now(KST).monthValue, perfRecognized = 15_000, perfCounted = true, perfTotalAfter = 285_000, perfTarget = 300_000,
    )

    // ── 취소 · 메모 — BE PaymentCancelService · PaymentMemoRequest와 같은 규칙 ──

    private val cancelLock = Mutex()
    private val cancelResults = mutableMapOf<Long, CancelResult>()
    private val cancelByKey = mutableMapOf<String, CancelResult>()
    private val memos = mutableMapOf<Long, String>()

    fun isCanceled(paymentId: Long): Boolean = paymentId in cancelResults

    fun memoOf(paymentId: Long): String? = memos[paymentId]

    private fun PaymentReceipt.withEdits(): PaymentReceipt {
        val done = cancelResults[paymentId]
        return copy(
            memo = memos[paymentId],
            status = if (done != null) PayStatus.CANCELED else status,
            canceledAtIso = done?.canceledAtIso ?: canceledAtIso,
            refunds = done?.refunds ?: refunds,
        )
    }

    /** 디버그 메뉴 '은행 오류'면 502 — BE처럼 결제는 승인 상태로 남아 다시 취소할 수 있다 */
    override suspend fun cancel(paymentId: Long, idempotencyKey: String): ApiResult<CancelResult> =
        withFault { cancelLock.withLock { cancelNow(paymentId, idempotencyKey) } }

    /** 한 줄로 세운다 — 같은 키는 처음 결과, 다른 키로 동시에 두 번이면 뒤 요청은 409 ALREADY_CANCELED */
    private suspend fun cancelNow(paymentId: Long, key: String): ApiResult<CancelResult> {
        delay(900)
        cancelByKey[key]?.let { previous ->
            return if (previous.paymentId == paymentId) ApiResult.Success(previous) else ApiResult.Failure(ApiError.Conflict("IDEMPOTENCY_KEY_REUSED"))
        }
        if (paymentId in cancelResults) return ApiResult.Failure(ApiError.Conflict("ALREADY_CANCELED"))
        val receipt = receiptOf(paymentId)
        if (receipt.isSeed) return ApiResult.Failure(ApiError.Conflict("PAYMENT_NOT_CANCELABLE", buildJsonObject { put("reason", "SEED_TRANSACTION") }))
        val now = OffsetDateTime.now(KST).truncatedTo(ChronoUnit.SECONDS).toString()
        val result = if (receipt.isCheck) {
            // 체크카드 — 출금액을 연결 계좌로 바로 입금하고 입금 뒤 잔액
            CancelResult(paymentId, now, excludedBills = 0, refunds = emptyList(), balanceAfter = (receipt.balanceAfter ?: 950_000) + (receipt.chargedAmount ?: receipt.amount))
        } else {
            // 신용카드 — 가짜 청구일은 다음 달이라 모두 청구 전 → 청구에서 빠진다
            CancelResult(paymentId, now, excludedBills = receipt.installmentMonths ?: 1, refunds = emptyList(), balanceAfter = null)
        }
        cancelResults[paymentId] = result
        cancelByKey[key] = result
        if (receipts.containsKey(paymentId)) receipt.cardId?.let { perf.addPayment(it, -receipt.amount, counted = receipt.perfCounted) }   // 실적 되돌림
        edits.markCanceled(paymentId, now)
        edits.changed()
        return ApiResult.Success(result)
    }

    override suspend fun saveMemo(paymentId: Long, memo: String?): ApiResult<String?> = withFault { saveMemoNow(paymentId, memo) }

    private suspend fun saveMemoNow(paymentId: Long, memo: String?): ApiResult<String?> {
        delay(400)
        val text = MemoRules.normalize(memo.orEmpty())
        if (text.any { Character.isISOControl(it) }) return ApiResult.Failure(ApiError.InvalidRequest("INVALID_REQUEST"))
        if (MemoRules.length(text) > MemoRules.MAX_LENGTH) return ApiResult.Failure(ApiError.InvalidRequest("MEMO_TOO_LONG"))
        if (text.isEmpty()) memos.remove(paymentId) else memos[paymentId] = text
        edits.changed()
        return ApiResult.Success(text.ifEmpty { null })
    }
}
