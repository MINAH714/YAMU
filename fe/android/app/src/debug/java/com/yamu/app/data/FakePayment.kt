package com.yamu.app.data

import com.yamu.app.core.format.KST
import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.session.DevSession
import com.yamu.app.core.session.HomeScenario
import com.yamu.app.core.session.PayScenario
import com.yamu.app.feature.link.data.brandOf
import com.yamu.app.feature.payment.data.ApproveOutcome
import com.yamu.app.feature.payment.data.CodeState
import com.yamu.app.feature.payment.data.Merchant
import com.yamu.app.feature.payment.data.PayCard
import com.yamu.app.feature.payment.data.PayCode
import com.yamu.app.feature.payment.data.PayCodeStatus
import com.yamu.app.feature.payment.data.PaymentReceipt
import com.yamu.app.feature.payment.data.PaymentRepository
import com.yamu.app.feature.payment.data.PaymentRequest
import com.yamu.app.feature.payment.data.ReceiptBenefit
import com.yamu.app.feature.payment.data.Recommendation
import com.yamu.app.feature.payment.data.declineMessage
import kotlinx.coroutines.delay
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
        Merchant(301, "야무카페 역삼점", "카페", isOnline = false, posCode = "POS-0301"),
        Merchant(302, "야무마트 강남점", "대형마트", isOnline = false, posCode = "POS-0302"),
        Merchant(303, "야무편의점 역삼역점", "편의점", isOnline = false, posCode = "POS-0303"),
        Merchant(304, "야무주유소 선릉점", "주유", isOnline = false, posCode = "POS-0304"),
        Merchant(305, "야무텔레콤 강남점", "통신", isOnline = false, posCode = "POS-0305"),
        Merchant(318, "야무몰", "온라인 쇼핑", isOnline = true, posCode = null),
    )
}

/**
 * debug 전용 가짜 결제 서버 — 코드 발급 → (디버그 메뉴 '결제 상태'대로) 3초 뒤 가짜 가맹점이 찍음 → 승인 · 거절.
 * 숫자는 프로토타입 P-06c · P-07과 같다(신한 생활 신용카드 카페 10% 1,500원 · 10월 실적 285,000 / 300,000원).
 */
@Singleton
class FakePaymentRepository @Inject constructor(private val perf: FakePerformanceStore) : PaymentRepository {

    private class Issued(val code: PayCode, val issuedAt: Long, val scenario: PayScenario) {
        var request: PaymentRequest? = null
        var closed = false
        var declines = 0
    }

    private val codes = mutableMapOf<String, Issued>()
    private val receipts = mutableMapOf<Long, PaymentReceipt>()
    private var nextPaymentId = 1001L

    private fun card(id: Long, issuer: String, color: String, name: String, last4: String, check: Boolean, benefit: Long, benefitName: String?, months: List<Int> = emptyList(), charged: Long? = null, perf: String? = null) =
        PayCard(id, brandOf(issuer, color = color), name, last4, check, benefit, benefitName, charged, months, benefitName?.let { "$it ${"%,d".format(benefit)}원" }, perf)

    private val shinhan get() = card(11, "신한카드", "#0046FF", "신한 생활 신용카드", "1021", false, 1_500, "카페 10% 할인", months = listOf(2, 3, 6), perf = "이번 달 실적 +15,000원")
    private val kb get() = card(12, "KB국민카드", "#FFBC00", "KB국민 적립 신용카드", "3310", false, 300, "2% 적립", perf = "이번 달 실적 +15,000원")

    private fun requestFor(scenario: PayScenario, paymentId: Long, deadline: String): PaymentRequest = when (scenario) {
        PayScenario.MART -> PaymentRequest(
            paymentId, "야무마트 강남점", "대형마트", 90_000, deadline,
            listOf(
                card(15, "하나카드", "#008C8C", "하나 마트·주유 신용카드", "5096", false, 4_500, "마트 5% 할인", months = listOf(2, 3, 6), perf = "이번 달 실적 +90,000원"),
                card(11, "신한카드", "#0046FF", "신한 생활 신용카드", "1021", false, 900, "1% 할인", months = listOf(2, 3, 6), perf = "이번 달 실적 +90,000원"),
            ),
        )
        PayScenario.CHECK_DECLINED -> PaymentRequest(
            paymentId, "야무편의점 역삼역점", "편의점", 15_000, deadline,
            listOf(
                card(16, "KB국민카드", "#60584C", "KB국민 체크카드", "8823", true, 750, "편의점 5% 즉시할인", charged = 14_250, perf = "할인받은 결제는 실적에서 빠져요"),
                shinhan.copy(benefitName = "편의점 3% 할인", netBenefit = 450, reason = "편의점 3% 할인 450원"),
            ),
        )
        else -> PaymentRequest(paymentId, "야무카페 역삼점", "카페", 15_000, deadline, listOf(shinhan, kb))
    }

    override suspend fun merchants(): ApiResult<List<Merchant>> = withFault { merchantsNow() }

    private suspend fun merchantsNow(): ApiResult<List<Merchant>> {
        delay(300)
        return ApiResult.Success(FakeMerchants.all)
    }

    override suspend fun recommend(merchantId: Long, amount: Long): ApiResult<Recommendation> = withFault(empty = { ApiResult.Success(Recommendation(FakeMerchants.all.firstOrNull { it.id == merchantId }?.name.orEmpty(), amount, emptyList(), isEstimate = false)) }) { recommendNow(merchantId, amount) }

    private suspend fun recommendNow(merchantId: Long, amount: Long): ApiResult<Recommendation> {
        delay(500)
        val m = FakeMerchants.all.firstOrNull { it.id == merchantId } ?: return ApiResult.Failure(ApiError.NotFound("MERCHANT_NOT_FOUND"))
        val cards = when (m.categoryName) {
            "카페" -> listOf(shinhan.copy(netBenefit = amount / 10), kb.copy(netBenefit = amount / 50))
            "대형마트" -> listOf(
                card(15, "하나카드", "#008C8C", "하나 마트·주유 신용카드", "5096", false, amount / 20, "마트 5% 할인"),
                kb.copy(netBenefit = amount / 50),
            )
            else -> listOf(kb.copy(netBenefit = amount / 50), shinhan.copy(netBenefit = amount / 100, benefitName = "1% 할인", reason = "1% 할인"))
        }
        return ApiResult.Success(Recommendation(m.name, amount, cards, isEstimate = false))
    }

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
            val deadline = OffsetDateTime.now(KST).plusSeconds(60).toString()
            issued.request = requestFor(issued.scenario, nextPaymentId++, deadline)
            return ApiResult.Success(PayCodeStatus(CodeState.SCANNED, issued.request))
        }
        return ApiResult.Success(PayCodeStatus(CodeState.ISSUED, null))
    }

    override suspend fun approve(request: PaymentRequest, card: PayCard, installmentMonths: Int?, idempotencyKey: String): ApiResult<ApproveOutcome> {
        delay(900)
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
            benefits = listOfNotNull(card.benefitName?.let { ReceiptBenefit(it, card.netBenefit) }),
            chargedAmount = card.chargedAmount ?: request.amount,
            balanceAfter = if (card.isCheck) 950_000 - (card.chargedAmount ?: request.amount) else null,
            billDateIso = if (card.isCheck) null else payDay.toString(),
            billAmount = if (card.isCheck) null else (request.amount - card.netBenefit) / (installmentMonths ?: 1),
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
        val receipt = receipts[paymentId] ?: FakePaymentHistory.receipt(paymentId) ?: PaymentReceipt(   // 결제 내역(S-05)에 없는 예전 결제
            paymentId = paymentId, merchantName = "야무카페 역삼점", categoryName = "카페", amount = 15_000,
            paidAtIso = OffsetDateTime.now(KST).minusHours(1).truncatedTo(ChronoUnit.MINUTES).toString(),
            cardId = 11, cardBrand = shinhan.brand, cardName = shinhan.name, cardLast4 = "1021", isCheck = false,
            approvalNo = "Y20261026093800938", installmentMonths = null, benefits = listOf(ReceiptBenefit("카페 10% 할인", 1_500)),
            chargedAmount = 15_000, balanceAfter = null, billDateIso = YearMonth.now(KST).plusMonths(1).atDay(14).toString(), billAmount = 13_500,
            perfMonth = LocalDate.now(KST).monthValue, perfRecognized = 15_000, perfCounted = true, perfTotalAfter = 285_000, perfTarget = 300_000,
        )
        return ApiResult.Success(receipt)
    }
}
