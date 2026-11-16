package com.yamu.app.feature.home.data

import com.yamu.app.core.format.parseDate
import com.yamu.app.core.format.toWon
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.ceil

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
 * 이번 달 YAMU 결제로 생긴 혜택(이용월 기준). [confirmed]는 카드사가 확정한 금액, [pending]은 아직 확정 전인 금액이다(HOME-04).
 * 두 값을 더한 숫자를 확정 금액처럼 쓰지 않는다. 포인트처럼 원화로 못 바꾼 혜택은 합계에 없고 [unconvertedCount]줄로만 안다(HOME-05)
 */
data class HomeBenefit(
    val month: YearMonth,
    val from: LocalDate,
    val to: LocalDate,
    val confirmed: Long,
    val pending: Long,
    /**
     * 이번 달 승인 결제가 한 건이라도 있는지 — 0원 혜택을 '결제 없음'과 '혜택 없음'으로 나눈다.
     * 새 홈 칸(benefitSummary)엔 결제 건수가 없어 모른다(null). 홈 recentPayment는 시드 결제도 잡아 대신 쓰지 않는다
     */
    val hasPayments: Boolean?,
    /** 종류별(할인 · 캐시백 · 적립 순서). 옛 서버(reports/monthly)는 종류를 안 줘서 비어 있다 */
    val byType: List<BenefitByType> = emptyList(),
    /** 원화로 못 바꿔 금액에 빠진 혜택 줄 수(BE unconvertedBenefitCount) */
    val unconvertedCount: Long = 0,
)

/** 혜택 종류 — BE type 문자열(DISCOUNT · CASHBACK · POINT)과 화면 이름 */
enum class HomeBenefitType(val label: String) { DISCOUNT("할인"), CASHBACK("캐시백"), POINT("적립") }

/** 종류 하나의 확정 · 확정 전(원화) */
data class BenefitByType(val type: HomeBenefitType, val confirmed: Long, val pending: Long, val unconvertedCount: Long)

/**
 * 새 홈 칸 → 화면 값(BE -490). 확정 전은 [HomeYamuBenefitDto.unconfirmedBenefit](= 전체 − 확정)을 그대로 쓴다.
 * 달 · 전체 · 확정 중 하나라도 없으면 0원으로 채우지 않고 null(그 칸만 실패로 본다)
 */
fun HomeYamuBenefitDto.toBenefit(today: LocalDate): HomeBenefit? {
    val month = yearMonth?.let { runCatching { YearMonth.parse(it) }.getOrNull() } ?: return null
    val total = totalBenefit ?: return null
    val confirmed = (confirmedBenefit ?: return null).coerceAtLeast(0)
    return HomeBenefit(
        month = month,
        from = month.atDay(1),
        to = if (YearMonth.from(today) == month) today else month.atEndOfMonth(),
        confirmed = confirmed,
        pending = (unconfirmedBenefit ?: (total - confirmed)).coerceAtLeast(0),
        hasPayments = null,
        byType = byType.mapNotNull { it.toByType() }.sortedBy { it.type.ordinal },
        unconvertedCount = unconvertedBenefitCount.coerceAtLeast(0),
    )
}

private fun HomeBenefitTypeDto.toByType(): BenefitByType? {
    val type = HomeBenefitType.entries.firstOrNull { it.name == type?.uppercase() } ?: return null
    val confirmed = (confirmedBenefit ?: return null).coerceAtLeast(0)
    return BenefitByType(
        type = type,
        confirmed = confirmed,
        pending = (unconfirmedBenefit ?: ((expectedBenefit ?: confirmed) - confirmed)).coerceAtLeast(0),
        unconvertedCount = unconvertedBenefitCount.coerceAtLeast(0),
    )
}

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
 * 그 밖은 '○○님, ○월 혜택을 모아 봤어요'와 기간만. 'YAMU로 결제한 것만 셌어요'는 설명하는 말투라 11/15에 뺐다
 */
fun homeGreeting(name: String?, needsLinking: Boolean, needsCard: Boolean, month: YearMonth, from: LocalDate, to: LocalDate): HomeGreeting {
    val who = "${name?.takeIf { it.isNotBlank() } ?: "야무"}님"
    return when {
        needsLinking -> HomeGreeting("$who, 반가워요", "계좌와 카드를 연결하면 혜택과 잔고를 모아 보여 드려요")
        needsCard -> HomeGreeting("$who, 반가워요", "카드를 등록하면 혜택을 모아 보여 드려요")
        else -> HomeGreeting("$who, ${month.monthValue}월 혜택을 모아 봤어요", periodText(from, to))
    }
}

/** '11/1~11/13' — 1일 하루면 '11/1' */
fun periodText(from: LocalDate, to: LocalDate): String {
    val a = "${from.monthValue}/${from.dayOfMonth}"
    return if (!to.isAfter(from)) a else "$a~${to.monthValue}/${to.dayOfMonth}"
}

/**
 * 히어로 금액 아래 안내 — 0원일 때만. 결제가 없으면 '결제 없음', 결제는 있는데 혜택이 없으면 '혜택 없음',
 * 결제가 있었는지 모르면(새 홈 칸) 둘을 아우르는 한 줄. 원화로 못 바꾼 혜택만 있으면 '없음'이라고 하지 않는다.
 * 확정 전 금액이 있으면 null(칩이 말한다)
 */
fun HomeBenefit.emptyNote(): String? = when {
    confirmed > 0 || pending > 0 -> null
    unconvertedCount > 0 -> "원화로 셀 수 있는 혜택은 아직 없어요"
    hasPayments == false -> "이번 달 YAMU 결제가 아직 없어요"
    hasPayments == true -> "이번 달 결제로 생긴 혜택이 아직 없어요"
    else -> "이번 달 YAMU 결제로 생긴 혜택이 아직 없어요"
}

/** 히어로 제목 '11월 이용 혜택' — 지급월이 아니라 그 달 결제로 생긴 혜택이라(487~490 명세) '받은'을 쓰지 않는다 */
fun benefitTitle(month: YearMonth?): String = "${month?.let { "${it.monthValue}월" } ?: "이번 달"} 이용 혜택"

/** 종류별 세 칸을 보일지 — 종류를 받았고 셀 것이 하나라도 있을 때(0원 · 결제 없음이면 위 안내 한 줄로 충분) */
val HomeBenefit.showsTypes: Boolean get() = byType.isNotEmpty() && (confirmed > 0 || pending > 0 || unconvertedCount > 0)

/**
 * 종류 칸 금액(11/14 결정: 확정만 — 세 칸 합이 큰 숫자와 같다). 확정이 0원이어도 확정 전 · 못 바꾼 혜택이 있으면
 * '0원'으로 쓰지 않는다(적립 0원 + 미환산 1건을 '적립 없음'으로 단정하지 않는다). 확정 전은 히어로 칩처럼 '예정'(11/16)
 */
fun BenefitByType.amountText(): String = when {
    confirmed > 0 -> "${confirmed.toWon()}원"
    pending > 0 -> "예정"
    unconvertedCount > 0 -> "환산 전"
    else -> "0원"
}

/** 원화로 못 바꾼 혜택이 있을 때 히어로 아래 한 줄 — 없으면 null */
fun HomeBenefit.unconvertedNote(): String? =
    "원화로 확인되는 혜택만 더했어요. 금액에 넣지 못한 혜택이 ${unconvertedCount}건 있어요".takeIf { unconvertedCount > 0 }

/**
 * 히어로 동전 수 — 확정 금액만 꽉 찬 동전으로 쌓는다(11/16: 확정 전 금액의 점선 동전은 뺐다).
 * 약 [WON_PER_COIN]원에 한 개(올림), 많아도 [MAX_BENEFIT_COINS]개까지
 */
fun benefitCoins(confirmed: Long): Int =
    if (confirmed <= 0) 0 else ceil(confirmed / WON_PER_COIN.toDouble()).toInt().coerceAtMost(MAX_BENEFIT_COINS)

const val WON_PER_COIN = 2_500L
const val MAX_BENEFIT_COINS = 24
