package com.yamu.app.feature.home.data

import com.yamu.app.core.format.directionParticle
import com.yamu.app.core.format.parseDate
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.ceil
import kotlin.math.roundToInt

// ── 홈 위쪽(v4 HOME-02 ~ 09, S15P31E103-480)이 따로 부르는 두 API — 홈이 쓰는 칸만 받는다 ──
// 리포트 저장소(ReportRepository.monthly)는 혜택을 'expected ?: actual' 하나로 합쳐 확정과 예상을 나눌 수 없어 홈이 직접 부른다

/** GET reports/monthly — BE MonthlyReportResponse 중 기간 · 결제 합계 · 혜택 합계 */
@Serializable
data class HomeMonthlyDto(
    val yearMonth: String? = null,
    val periodTo: String? = null,
    val totalSpent: Long = 0,
    val benefitSummary: HomeBenefitSummaryDto? = null,
)

/** expected = 승인 결제의 혜택 원화 합계(확정 전 포함), actual = 그중 확정된 합계(BE MonthlyReportResponse.BenefitSummary) */
@Serializable
data class HomeBenefitSummaryDto(val expected: Long? = null, val actual: Long? = null)

/** GET users/me — 결제 설정만 */
@Serializable
data class HomeMeDto(val paymentSettings: HomePaymentSettingsDto? = null)

@Serializable
data class HomePaymentSettingsDto(val autoSelect: Boolean = true, val defaultUserCardId: Long? = null)

/**
 * 이번 달 YAMU 결제로 받은 혜택. [confirmed]는 카드사가 확정한 금액, [pending]은 아직 확정 전인 금액이다(HOME-04).
 * 두 값을 더한 숫자를 '받은 혜택'으로 쓰지 않는다. 포인트처럼 원화로 못 바꾼 혜택은 BE 합계에 없다(HOME-05)
 */
data class HomeBenefit(
    val month: YearMonth,
    val from: LocalDate,
    val to: LocalDate,
    val confirmed: Long,
    val pending: Long,
    /** 이번 달 승인 결제가 한 건이라도 있는지(결제 금액 합계 > 0) — 0원 혜택을 '결제 없음'과 '혜택 없음'으로 나눈다 */
    val hasPayments: Boolean,
)

/** 결제 설정(HOME-09). 못 읽었으면 화면은 null로 받아 자동 선택이 켜졌다고도 꺼졌다고도 말하지 않는다 */
data class PaySettings(val autoSelect: Boolean, val defaultUserCardId: Long?)

/**
 * 서버 값 → 화면 값. 확정 전 = 예상 합계 − 확정 합계(0 아래로는 내리지 않는다). 기간 끝은 서버 periodTo,
 * 없으면 이번 달은 오늘 · 지난 달은 말일. 혜택 합계 칸이 통째로 없으면 0원으로 지어내지 않고 null(불러오기 실패로 본다)
 */
fun HomeMonthlyDto.toBenefit(today: LocalDate): HomeBenefit? {
    val summary = benefitSummary ?: return null
    val month = yearMonth?.let { runCatching { YearMonth.parse(it) }.getOrNull() } ?: YearMonth.from(today)
    val confirmed = (summary.actual ?: 0).coerceAtLeast(0)
    val expected = summary.expected ?: confirmed
    val to = parseDate(periodTo) ?: if (YearMonth.from(today) == month) today else month.atEndOfMonth()
    return HomeBenefit(
        month = month,
        from = month.atDay(1),
        to = to,
        confirmed = confirmed,
        pending = (expected - confirmed).coerceAtLeast(0),
        hasPayments = totalSpent > 0,
    )
}

fun HomeMeDto.toPaySettings(): PaySettings? = paymentSettings?.let { PaySettings(it.autoSelect, it.defaultUserCardId) }

// ── 화면 문구(순수 함수 — 단위 테스트) ──

/** 인사 두 줄 */
data class HomeGreeting(val title: String, val sub: String)

/**
 * 인사(HOME-02 앞). 연결 전 · 카드 없음은 혜택을 모을 수 없어 '반가워요'로 맞고 무엇을 하면 되는지 말한다(시안 그대로).
 * 그 밖은 '○○님, ○월 혜택을 모아 봤어요'와 집계 범위(YAMU 결제만 · 기간, COMMON-01)
 */
fun homeGreeting(name: String?, needsLinking: Boolean, needsCard: Boolean, month: YearMonth, from: LocalDate, to: LocalDate): HomeGreeting {
    val who = "${name?.takeIf { it.isNotBlank() } ?: "야무"}님"
    return when {
        needsLinking -> HomeGreeting("$who, 반가워요", "계좌와 카드를 연결하면 혜택과 잔고를 모아 보여 드려요")
        needsCard -> HomeGreeting("$who, 반가워요", "카드를 등록하면 혜택을 모아 보여 드려요")
        else -> HomeGreeting("$who, ${month.monthValue}월 혜택을 모아 봤어요", "YAMU로 결제한 것만 셌어요 · ${periodText(from, to)}")
    }
}

/** '11/1~11/13' — 1일 하루면 '11/1' */
fun periodText(from: LocalDate, to: LocalDate): String {
    val a = "${from.monthValue}/${from.dayOfMonth}"
    return if (!to.isAfter(from)) a else "$a~${to.monthValue}/${to.dayOfMonth}"
}

/**
 * 히어로 금액 아래 안내 — 0원일 때만. 결제가 없으면 '결제 없음', 결제는 있는데 혜택이 없으면 '혜택 없음'.
 * 확정 전 금액이 있으면 null(칩이 말한다)
 */
fun HomeBenefit.emptyNote(): String? = when {
    confirmed > 0 || pending > 0 -> null
    !hasPayments -> "이번 달 YAMU 결제가 아직 없어요"
    else -> "이번 달 결제에서 받은 혜택이 아직 없어요"
}

/** 결제 칸 안내 한 줄과 그 줄이 주의(자동 선택 꺼짐)인지 */
data class PayNote(val text: String, val autoOff: Boolean)

/**
 * 'YAMU로 결제하기' 안내(HOME-08 · 09). 바코드를 열기 전에 특정 카드가 최적이라고 말하지 않는다.
 * 자동 선택을 끈 사람에게는 기본 카드로 결제한다고 바꿔 보이고, 설정을 못 읽었으면 어느 쪽인지 단정하지 않는다
 */
fun payNote(settings: PaySettings?, cardName: (Long) -> String?): PayNote = when {
    settings == null -> PayNote("결제 코드를 열면 결제 설정대로 카드를 골라요", autoOff = false)
    settings.autoSelect -> PayNote("결제할 곳과 금액이 정해지면 실적과 남은 혜택 한도를 따져 카드를 골라요", autoOff = false)
    else -> {
        val name = settings.defaultUserCardId?.let(cardName)?.takeIf { it.isNotBlank() }
        PayNote(
            if (name != null) "자동 선택이 꺼져 있어요. 기본 카드인 $name${name.cardParticle()} 결제해요" else "자동 선택이 꺼져 있어 기본 카드로 결제해요",
            autoOff = true,
        )
    }
}

/** '로 · 으로' — 끝의 닫는 괄호 · 따옴표는 건너뛰고 본다('ZERO Edition3(포인트형)으로') */
private fun String.cardParticle(): String = trimEnd(')', ']', '」', '"', '\'', '.', ' ').directionParticle()

/** 결제 칸 제목 아래 한 줄 — 자동 선택이 켜졌을 때만(꺼졌거나 모르면 안내 줄이 말한다) */
fun paySubtitle(settings: PaySettings?): String? = "카드는 YAMU가 알아서 골라요".takeIf { settings?.autoSelect == true }

/**
 * 히어로 동전 수 — 확정은 꽉 찬 동전, 확정 전은 점선 동전(11/13 시안에서 고름). 약 [WON_PER_COIN]원에 한 개(올림),
 * 둘 합이 [MAX_BENEFIT_COINS]개를 넘으면 비율대로 줄이되 0원이 아닌 쪽은 한 개 이상 남긴다
 */
fun benefitCoins(confirmed: Long, pending: Long): Pair<Int, Int> {
    fun count(won: Long): Int = if (won <= 0) 0 else ceil(won / WON_PER_COIN.toDouble()).toInt().coerceAtMost(MAX_BENEFIT_COINS)
    val solid = count(confirmed)
    val ghost = count(pending)
    val total = solid + ghost
    if (total <= MAX_BENEFIT_COINS) return solid to ghost
    val scaled = (solid * MAX_BENEFIT_COINS / total.toDouble()).roundToInt()
        .coerceIn(if (solid > 0) 1 else 0, if (ghost > 0) MAX_BENEFIT_COINS - 1 else MAX_BENEFIT_COINS)
    return scaled to MAX_BENEFIT_COINS - scaled
}

const val WON_PER_COIN = 2_500L
const val MAX_BENEFIT_COINS = 24
