package com.yamu.app.data

import com.yamu.app.core.format.KST
import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.feature.link.data.brandOf
import com.yamu.app.feature.performance.data.BenefitLimits
import com.yamu.app.feature.performance.data.CardPerf
import com.yamu.app.feature.performance.data.LimitCard
import com.yamu.app.feature.performance.data.LimitTag
import com.yamu.app.feature.performance.data.MonthRecognized
import com.yamu.app.feature.performance.data.MovableItem
import com.yamu.app.feature.performance.data.PerfAnalysis
import com.yamu.app.feature.performance.data.PerfDecision
import com.yamu.app.feature.performance.data.PerfDetail
import com.yamu.app.feature.performance.data.PerfGuide
import com.yamu.app.feature.performance.data.PerfOverview
import com.yamu.app.feature.performance.data.PerfStep
import com.yamu.app.feature.performance.data.PerfTier
import com.yamu.app.feature.performance.data.PerformanceRepository
import com.yamu.app.feature.performance.data.toOverview
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * debug 전용 가짜 실적 장부 — 가짜 사용자가 등록한 카드만(가짜 은행 FakeBank와 같은 userCardId 11~17). 시드 사용자는 6장(11~16).
 * 가짜 결제(FakePaymentRepository)가 승인되면 여기에 더한다 → 실적관리로 돌아오면 값 · 판정이 바뀐다(11 문서 7-5).
 * 실적 숫자는 프로토타입 R-01 · R-03 그대로, 카드 · 실적 기준 · 혜택 · 한도는 BE 카드 시드 상품으로.
 */
@Singleton
class FakePerformanceStore @Inject constructor(private val bank: FakeBank) {

    /** kind = 서버가 정했을 판정(기준을 넘으면 달성), atRisk = 서버 status AT_RISK(지금 추세면 미달) */
    class Card(
        val id: Long, val issuer: String, val color: String, val name: String,
        val target: Long?, var recognized: Long, val kind: PerfGuide?, val nextMonthBenefit: String?,
        val atRisk: Boolean = false,
    ) {
        var decision: PerfDecision? = null
        var recentChange: Long? = null
        var recentAt: String? = null
        var moved = false
    }

    // 실적 기준 · 혜택은 BE 카드 시드 값(LIKIT Shop 40만 · 톡톡 · iD ON · Mr.Life 30만 · ZERO 실적 조건 없음).
    // 노리2는 구간이 다섯(20만~80만)이라 서버처럼 목표가 '지금 실적보다 큰 가장 작은 기준'(21만 → 30만)이고, 판정 없이 AT_RISK
    private val seedCards = listOf(
        Card(13, "롯데카드", "#E60012", "LOCA LIKIT Shop", 400_000, 320_000, PerfGuide.ACHIEVE_RECOMMENDED, "쿠팡·편의점 60%"),
        Card(11, "KB국민카드", "#FFBC00", "KB국민 청춘대로 톡톡카드", 300_000, 270_000, PerfGuide.KEEP_NATURAL, "스타벅스 50%"),
        Card(14, "삼성카드", "#1428A0", "삼성 iD ON 카드", 300_000, 180_000, PerfGuide.EXTRA_SPENDING_NOT_RECOMMENDED, "교통 10%", atRisk = true),
        Card(15, "신한카드", "#0046FF", "신한카드 Mr.Life", 300_000, 320_000, null, "편의점·마트 10%"),
        Card(16, "KB국민카드", "#60584C", "KB국민 노리2 체크카드(KB Pay)", 300_000, 210_000, null, "GS25·CU 5%", atRisk = true),
        Card(12, "현대카드", "#1A1A1A", "현대카드ZERO Edition3(포인트형)", null, 150_000, null, null),
    )

    /** 위 목록에 없는 카드(새로 나온 트래블로그 17 등) — 실적 기준을 모르고 이번 달 실적 0원으로 시작 */
    private val others = mutableMapOf<Long, Card>()

    /**
     * 가짜 사용자가 등록한 카드만(S15P31E103-454) — 내 카드 · 실적관리 · 결제가 카드 관리와 같은 장수를 보인다.
     * 순서는 위 목록 순서, 그 뒤에 목록에 없는 카드
     */
    val cards: List<Card>
        get() {
            val registered = bank.cardViews()
            val ids = registered.map { it.userCardId }.toSet()
            return seedCards.filter { it.id in ids } + registered.filter { r -> seedCards.none { it.id == r.userCardId } }.map { r ->
                others.getOrPut(r.userCardId) { Card(r.userCardId, r.issuer, r.color, r.product, target = null, recognized = 0, kind = null, nextMonthBenefit = null) }
            }
        }

    fun card(id: Long) = cards.firstOrNull { it.id == id }

    /** 가짜 결제 승인 → 실적 반영. 실적에서 빠지는 결제(즉시할인 체크 등)는 [counted] false */
    fun addPayment(userCardId: Long, amount: Long, counted: Boolean) {
        val c = card(userCardId) ?: return
        cards.forEach { it.recentChange = null; it.recentAt = null }
        if (!counted) return
        c.recognized += amount
        c.recentChange = amount
        c.recentAt = OffsetDateTime.now(KST).truncatedTo(ChronoUnit.MINUTES).toString()
    }

    fun achieved(c: Card) = c.target != null && c.recognized >= c.target
}

@Singleton
class FakePerformanceRepository @Inject constructor(private val store: FakePerformanceStore, private val bank: FakeBank) : PerformanceRepository {

    private var last: PerfOverview? = null
    private val month = YearMonth.now(KST)
    private val daysLeft get() = month.atEndOfMonth().dayOfMonth - LocalDate.now(KST).dayOfMonth

    private fun FakePerformanceStore.Card.toCardPerf(): CardPerf {
        val achieved = store.achieved(this)
        return CardPerf(
            userCardId = id, brand = brandOf(issuer, color = color), name = issuer,
            target = target, targetLabel = target?.let { "${it / 10_000}만 원 이상" },
            recognized = recognized, remaining = target?.let { (it - recognized).coerceAtLeast(0) } ?: 0,
            achieved = achieved, noTarget = target == null,
            guide = if (achieved) PerfGuide.ALREADY_ACHIEVED else kind, decision = decision,
            provisional = false, recentChange = recentChange, recentAtIso = recentAt, nickname = name,
            atRisk = atRisk && !achieved,
        )
    }

    override suspend fun overview(): ApiResult<PerfOverview> = withFault(empty = { ApiResult.Success(emptyList<CardPerf>().toOverview(month.toString(), daysLeft, OffsetDateTime.now(KST).toString())) }) { overviewNow() }

    private suspend fun overviewNow(): ApiResult<PerfOverview> {
        delay(500)
        val overview = store.cards.map { it.toCardPerf() }.toOverview(month.toString(), daysLeft, OffsetDateTime.now(KST).toString())
        last = overview
        return ApiResult.Success(overview)
    }

    override fun cachedOverview(): PerfOverview? = last

    override suspend fun detail(userCardId: Long): ApiResult<PerfDetail> = withFault { detailNow(userCardId) }

    private suspend fun detailNow(userCardId: Long): ApiResult<PerfDetail> {
        delay(400)
        val c = store.card(userCardId) ?: return ApiResult.Failure(ApiError.NotFound("RESOURCE_NOT_FOUND"))
        // 구간 금액은 서버(BE !227)처럼 그 실적일 때 한 달 최대 혜택(누적). 모르면 null
        val tiers = when (c.id) {
            13L -> listOf(PerfTier("40만 원 이상", "쿠팡·편의점·미용실 60% 할인", 13_000, c.recognized >= 400_000, 400_000))   // LIKIT Shop 공유 월 한도 13,000
            16L -> listOf(200_000L to 20_000L, 300_000L to 20_000L, 400_000L to 30_000L, 600_000L to 40_000L, 800_000L to 45_000L)   // 노리2
                .map { (threshold, max) -> PerfTier("${threshold / 10_000}만 원 이상", c.nextMonthBenefit, max, c.recognized >= threshold, threshold) }
            else -> c.target?.let { listOf(PerfTier("${it / 10_000}만 원 이상", c.nextMonthBenefit, null, c.recognized >= it, it)) }.orEmpty()
        }
        val achieved = store.achieved(c)
        val remaining = c.target?.let { (it - c.recognized).coerceAtLeast(0) } ?: 0
        return ApiResult.Success(
            PerfDetail(
                userCardId = c.id, month = month.monthValue, target = c.target, targetLabel = c.target?.let { "${it / 10_000}만 원 이상" },
                recognized = c.recognized, remaining = remaining,
                achieved = achieved, noTarget = c.target == null, tiers = tiers,
                // 서버 nextMonthImpact.message와 같은 말투
                nextMonthMessage = c.target?.let { target ->
                    if (achieved) "다음 달에 ${target / 10_000}만원 이상 구간 혜택을 받아요"
                    else "${"%,d".format(remaining)}원 더 쓰면 다음 달 ${target / 10_000}만원 이상 구간 혜택을 받아요"
                },
                atRisk = c.atRisk && !achieved,
            ),
        )
    }

    /** 지난달 — 실적 조건이 있는 카드만, 이번 달보다 12만 원 더 쓴 달로(S15P31E103-602) */
    override suspend fun recognizedIn(yearMonth: String): ApiResult<MonthRecognized> = withFault {
        delay(300)
        ApiResult.Success(MonthRecognized(yearMonth, store.cards.filter { it.target != null }.associate { it.id to it.recognized + 120_000 }))
    }

    override suspend fun analysis(userCardId: Long): ApiResult<PerfAnalysis> = withFault { analysisNow(userCardId) }

    private suspend fun analysisNow(userCardId: Long): ApiResult<PerfAnalysis> {
        delay(500)
        val c = store.card(userCardId) ?: return ApiResult.Failure(ApiError.NotFound("RESOURCE_NOT_FOUND"))
        val target = c.target ?: return ApiResult.Failure(ApiError.DataInsufficient("DATA_INSUFFICIENT"))
        // 판정 없는 기준 전 카드(노리2) — 지금 실서버처럼 #18이 없다(BE 375 전)
        if (!store.achieved(c) && c.kind == null) return ApiResult.Failure(ApiError.NotSupported("API_NOT_FOUND"))
        val next = month.plusMonths(1).monthValue
        val version = OffsetDateTime.now(KST).truncatedTo(ChronoUnit.MINUTES).toString()
        if (store.achieved(c) || c.kind == null) {
            return ApiResult.Success(
                PerfAnalysis(c.id, month.monthValue, PerfGuide.ALREADY_ACHIEVED, "이번 달 실적을 채웠어요 · ${next}월에도 ${c.nextMonthBenefit} 혜택을 받아요",
                    target, c.recognized, 0, null, listOfNotNull(c.nextMonthBenefit), emptyList(), null, null, c.decision, false, version),
            )
        }
        val natural = when (c.kind) {        // 월말까지 자연 소비 예측(최근 3개월 같은 기간 평균 — 가짜)
            PerfGuide.ACHIEVE_RECOMMENDED -> 50_000L
            PerfGuide.KEEP_NATURAL -> 90_000L
            else -> 40_000L
        }
        val projected = c.recognized + natural + if (c.moved) 35_000 else 0   // 옮긴 통신비는 이 카드 실적으로
        val shortfall = (target - projected).coerceAtLeast(0)
        val analysis = when (c.kind) {
            PerfGuide.ACHIEVE_RECOMMENDED -> {
                val deadline = LocalDate.now(KST).plusDays(2)
                PerfAnalysis(
                    c.id, month.monthValue, c.kind, if (c.moved) "통신비를 옮겨 두면 월말에 기준을 넘어요" else "통신비만 옮기면 더 쓰지 않고 채워요", target, projected, shortfall, 13_000,
                    listOf("쿠팡·편의점 60% 할인 최대 13,000원"),
                    listOf(
                        PerfStep("남은 실적", target - c.recognized, "기준 ${"%,d".format(target)} − 인정 ${"%,d".format(c.recognized)}"),
                        PerfStep("월말까지 자연 소비 예측", natural, "최근 3개월 같은 기간 평균"),
                        PerfStep("예정 지출로 채울 수 있는 돈", 35_000, "잔고예보 예정 지출 4건 중 통신비 1건"),
                        PerfStep("그래도 모자라는 돈", (shortfall - 35_000).coerceAtLeast(0), null),
                        PerfStep("달성하면 더 받는 혜택", 13_000, "${next}월 쿠팡·편의점 60% 할인 한도"),
                        PerfStep("판단", null, "추가 지출 0원으로 달성 → 실적 달성 추천"),
                    ),
                    if (c.moved) null else MovableItem(318, "통신비", 35_000, deadline.toString(), "통신사 반영 1~2영업일"),
                    projectedIfMoved = null, decision = c.decision, isEstimate = true, version = version,
                )
            }
            PerfGuide.KEEP_NATURAL -> PerfAnalysis(
                c.id, month.monthValue, c.kind, "평소대로 쓰면 이번 달 실적을 채워요", target, projected, shortfall, null,
                listOfNotNull(c.nextMonthBenefit),
                listOf(
                    PerfStep("남은 실적", target - c.recognized, null),
                    PerfStep("월말까지 자연 소비 예측", natural, "최근 3개월 같은 기간 평균"),
                    PerfStep("그래도 모자라는 돈", shortfall, null),
                    PerfStep("판단", null, "평소 소비로 달성 → 자연 소비 유지"),
                ),
                null, decision = c.decision, isEstimate = true, version = version,
            )
            else -> PerfAnalysis(
                c.id, month.monthValue, PerfGuide.EXTRA_SPENDING_NOT_RECOMMENDED, "이번 달은 채우지 않는 게 유리해요", target, projected, shortfall, 3_000,
                listOfNotNull(c.nextMonthBenefit),
                listOf(
                    PerfStep("남은 실적", target - c.recognized, null),
                    PerfStep("월말까지 자연 소비 예측", natural, "최근 3개월 같은 기간 평균"),
                    PerfStep("그래도 모자라는 돈", shortfall, "필요 없는 소비로 채워야 하는 돈"),
                    PerfStep("달성하면 더 받는 혜택", 3_000, "${next}월 ${c.nextMonthBenefit}"),
                    PerfStep("판단", null, "더 써야 하는 돈 > 더 받는 혜택 → 추가 소비 비추천"),
                ),
                null, decision = c.decision, isEstimate = true, version = version,
            )
        }
        // what-if(옮겼다고 가정) — 가짜 서버가 바로 계산해서 같이 준다
        return ApiResult.Success(analysis.movable?.let { analysis.copy(projectedIfMoved = projected + it.amount) } ?: analysis)
    }

    override suspend fun decide(userCardId: Long, decision: PerfDecision?, analysisVersion: String?): ApiResult<Unit> {
        delay(400)
        store.card(userCardId)?.decision = decision
        return ApiResult.Success(Unit)
    }

    override suspend fun moveCashflow(cashflowItemId: Long, userCardId: Long): ApiResult<Unit> {
        delay(400)
        store.card(userCardId)?.moved = true
        return ApiResult.Success(Unit)
    }

    override suspend fun limits(): ApiResult<BenefitLimits> = withFault(empty = { ApiResult.Success(BenefitLimits(month.monthValue, remaining = 0, received = 0, cards = emptyList())) }) { limitsNow() }

    private suspend fun limitsNow(): ApiResult<BenefitLimits> {
        delay(400)
        fun c(id: Long, issuer: String, color: String, name: String, remaining: Long?, tag: LimitTag?, reason: String? = null) =
            LimitCard(id, brandOf(issuer, color = color), name, remaining, tag, reason)
        // 등록한 카드만(S15P31E103-454). 받은 혜택 30,900원은 시드 사용자의 이번 달 결제라 새 사용자는 0원
        val registered = bank.registeredCardIds().toSet()
        val cards = listOf(
            c(16, "KB국민카드", "#60584C", "KB 노리2 KB Pay", 1_100, LimitTag.NEAR_END),   // GS25·CU 월 2,000
            c(15, "신한카드", "#0046FF", "신한 Mr.Life", 6_700, null),                     // TIME 서비스 월 10,000
            c(11, "KB국민카드", "#FFBC00", "KB 톡톡", 5_800, null),                        // 스타벅스 월 10,000
            c(14, "삼성카드", "#1428A0", "삼성 iD ON", 2_600, null),                       // 생활 공유 월 10,000
            c(13, "롯데카드", "#E60012", "LOCA LIKIT Shop", 0, LimitTag.EXHAUSTED),        // 공유 월 13,000
            c(12, "현대카드", "#1A1A1A", "현대 ZERO 포인트형", null, LimitTag.UNLIMITED),
        ).filter { it.userCardId in registered }
        return ApiResult.Success(
            BenefitLimits(
                month.monthValue, remaining = cards.sumOf { it.remaining ?: 0 }, received = if (bank.seeded) 30_900 else 0,
                cards = cards,
            ),
        )
    }
}
