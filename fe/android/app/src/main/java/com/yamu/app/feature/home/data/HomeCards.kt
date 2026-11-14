package com.yamu.app.feature.home.data

import com.yamu.app.core.format.KST
import com.yamu.app.core.format.parseDate
import com.yamu.app.core.format.toManWon
import com.yamu.app.core.format.toWon
import com.yamu.app.feature.link.data.AccountDto
import com.yamu.app.feature.link.data.Brand
import com.yamu.app.feature.link.data.lastDigits
import com.yamu.app.feature.performance.data.CardPerf
import com.yamu.app.feature.performance.data.LimitCardDto
import com.yamu.app.feature.performance.data.LimitDto
import com.yamu.app.feature.performance.data.PerfGuide
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime

// ── 홈 아래쪽(v4 HOME-10 ~ 15, S15P31E103-481) ──
// 실적은 GET users/me/card-performances(#65), 한도는 GET benefit-limits(#32)를 홈이 직접 부른다.
// 실적관리 저장소의 한도 값(LimitCard)은 한 카드의 여러 한도를 더해 버리고 이름을 버려 HOME-10('근거 없이 하나로 더하지 않는다')에 못 쓴다

/** 내 카드 혜택 현황 · 놓치기 아까운 혜택의 재료. [limits]가 null이면 한도 API만 실패한 것 */
data class HomeCardsData(val cards: List<CardPerf>, val limits: List<LimitCardDto>?)

/** 카드 줄 실적 칩 — 색 대신 글자로도 상태를 말한다(COMMON-11) */
enum class PerfChipTone { Done, Todo, None }

data class PerfChip(val text: String, val tone: PerfChipTone)

/** 카드 줄 하나(HOME-10) — [opens]는 R-02로 갈 수 있는지(실적 조건 없는 카드는 R-02에 보일 실적이 없어 실적관리처럼 열지 않는다) */
data class HomeCardRow(
    val userCardId: Long,
    val name: String,
    val brand: Brand,
    val productName: String?,
    val chip: PerfChip,
    val limitLine: String?,
    val opens: Boolean,
)

/** 홈에 보일 카드 수 — 3장 + 전체보기(11/13 시안에서 고름) */
const val HOME_CARD_ROWS = 3

/** 카드 목록 순서 그대로 [HOME_CARD_ROWS]장 */
fun HomeCardsData.rows(): List<HomeCardRow> {
    val limitsById = limits?.associateBy { it.userCardId }
    return cards.take(HOME_CARD_ROWS).map { card ->
        HomeCardRow(
            userCardId = card.userCardId,
            name = card.nickname?.takeIf { it.isNotBlank() } ?: card.name,
            brand = card.brand,
            productName = card.nickname,
            chip = card.perfChip(),
            // 한도 API만 실패했으면 줄에 한도를 쓰지 않는다(0원 · 한도 없음으로 지어내지 않는다)
            limitLine = limitsById?.let { limitLine(it[card.userCardId]) },
            opens = !card.noTarget,
        )
    }
}

/** 실적 칩 — 달성 · 실적까지 ○원 · 실적 조건 없음 */
fun CardPerf.perfChip(): PerfChip = when {
    noTarget -> PerfChip("실적 조건 없음", PerfChipTone.None)
    achieved -> PerfChip("실적 달성", PerfChipTone.Done)
    else -> PerfChip("실적까지 ${remaining.toWon()}원", PerfChipTone.Todo)
}

private val OPEN = setOf("AVAILABLE", "NEAR_END")

/**
 * 카드 줄 한도 한 줄(HOME-10 · COMMON-07). 서로 다른 한도를 더하지 않고 남은 금액이 가장 큰 한도 하나를 이름과 함께 적고, 더 있으면 '외 n곳'.
 * 금액을 모르는 한도(횟수 · 포인트)는 0원이 아니라 '확인할 수 없음'. 한도 줄이 없으면 BE 약속대로 '한도 없음'.
 * 카드가 한도 응답에 없으면(새로 등록 중 등) 아무것도 쓰지 않는다
 */
fun limitLine(card: LimitCardDto?): String? {
    card ?: return null
    val limits = card.limits
    if (limits.isEmpty()) return "한도 없음"
    val open = limits.filter { it.status.uppercase() in OPEN }
    val known = open.filter { (it.remainingAmount ?: 0) > 0 }.maxByOrNull { it.remainingAmount ?: 0 }
    val unknown = open.firstOrNull { it.remainingAmount == null }
    val pick: Pair<LimitDto, String> = when {
        known != null -> known to "${known.name} 남은 한도 ${known.remainingAmount!!.toWon()}원"
        unknown != null -> unknown to "${unknown.name} 남은 한도 확인할 수 없음"
        limits.all { it.status.equals("LOCKED", ignoreCase = true) } -> return "이번 달 한도 잠김"
        else -> return "이번 달 한도를 다 썼어요"
    }
    val others = limits.size - 1
    return if (others > 0) "${pick.second} 외 ${others}곳" else pick.second
}

/** 놓치기 아까운 혜택 '남은 한도' 한 줄 — 카드 · 한도 이름과 확인된 남은 금액 */
data class RemainingLimit(val cardName: String, val limitName: String, val amount: Long)

/** '조건을 채우면' 한 줄 */
data class ConditionLine(val cardName: String, val text: String)

data class MissedBenefits(val remaining: List<RemainingLimit>, val conditions: List<ConditionLine>) {
    /** 확인된 것이 하나도 없으면 칸을 숨긴다(HOME-12) */
    val isEmpty: Boolean get() = remaining.isEmpty() && conditions.isEmpty()
}

const val MISSED_REMAINING_ROWS = 3
const val MISSED_CONDITION_ROWS = 2

/**
 * 놓치기 아까운 혜택(HOME-12 · 13).
 * - 남은 한도: 이번 달 쓸 수 있는(AVAILABLE · NEAR_END) 한도 중 남은 금액을 아는 것만, 많은 순 [MISSED_REMAINING_ROWS]줄.
 *   금액을 모르는 한도는 '혜택 가능'으로 단정하지 않으려고 넣지 않는다(카드 줄이 '확인할 수 없음'으로 말한다)
 * - 조건을 채우면: 실적 목표를 아직 못 채운 카드 중 서버 판정이 '추가 소비 비추천'이 아닌 것, 남은 금액 적은 순 [MISSED_CONDITION_ROWS]줄.
 *   이번 달 실적이 다음 달 혜택을 정한다(BE 한도 잠김은 지난달 실적 기준이라 이번 달엔 못 푼다). 더 쓰라는 말은 하지 않는다
 */
fun HomeCardsData.missed(): MissedBenefits {
    val names = cards.associate { it.userCardId to (it.nickname?.takeIf { n -> n.isNotBlank() } ?: it.name) }
    val remaining = limits.orEmpty().flatMap { card ->
        val cardName = names[card.userCardId] ?: card.nickname ?: "카드"
        card.limits
            .filter { it.status.uppercase() in OPEN && (it.remainingAmount ?: 0) > 0 }
            .map { RemainingLimit(cardName, it.name, it.remainingAmount!!) }
    }.sortedByDescending { it.amount }.take(MISSED_REMAINING_ROWS)
    val conditions = cards
        .filter { it.pending && it.remaining > 0 && it.guide != PerfGuide.EXTRA_SPENDING_NOT_RECOMMENDED }
        .sortedBy { it.remaining }
        .take(MISSED_CONDITION_ROWS)
        .map { card -> ConditionLine(names.getValue(card.userCardId), conditionText(card)) }
    return MissedBenefits(remaining, conditions)
}

/** '이번 달 실적이 30만원 이상이면 다음 달 혜택 조건을 채워요 (지금 255,000원)' — 목표 표시가 없으면 금액으로 */
fun conditionText(card: CardPerf): String {
    val target = card.targetLabel?.takeIf { it.isNotBlank() } ?: card.target?.let { "${it.toManWon()} 이상" } ?: "목표"
    return "이번 달 실적이 ${target}이면 다음 달 혜택 조건을 채워요 (지금 ${card.recognized.toManWon()})"
}

// ── 잔고예보 요약(HOME-14 · 15) ──

// ── 카드별 이번 달 받은 혜택(BE -489 !316) — GET reports/monthly/benefits?yearMonth=&source=YAMU의 cards만 ──

/** 혜택 리포트 중 cards. 옛 서버엔 cards가 없다(null → 카드 줄에 붙이지 않음) */
@Serializable
data class HomeBenefitReportDto(val cards: List<HomeCardBenefitDto>? = null)

/** 카드 하나(userCardId별, 같은 상품 두 장도 따로). 드물게 카드를 알 수 없는 줄은 userCardId가 null */
@Serializable
data class HomeCardBenefitDto(
    val userCardId: Long? = null,
    val expectedBenefit: Long? = null,
    val confirmedBenefit: Long? = null,
    val unconfirmedBenefit: Long? = null,
    val unconvertedBenefitCount: Long = 0,
)

/** 카드 줄에 붙일 이번 달 혜택 — 확정 · 확정 전 · 원화로 못 바꾼 줄 수 */
data class CardBenefit(val confirmed: Long, val pending: Long, val unconvertedCount: Long)

/**
 * userCardId → 혜택. 카드를 알 수 없는 줄(userCardId null)은 홈 카드 줄과 이을 수 없어 뺀다(S-04 합계에는 남는다).
 * 확정 금액이 없는 줄도 0원으로 채우지 않고 뺀다. cards가 없으면(옛 서버) null
 */
fun HomeBenefitReportDto.toCardBenefits(): Map<Long, CardBenefit>? = cards?.mapNotNull { c ->
    val id = c.userCardId ?: return@mapNotNull null
    val confirmed = (c.confirmedBenefit ?: return@mapNotNull null).coerceAtLeast(0)
    id to CardBenefit(
        confirmed = confirmed,
        pending = (c.unconfirmedBenefit ?: ((c.expectedBenefit ?: confirmed) - confirmed)).coerceAtLeast(0),
        unconvertedCount = c.unconvertedBenefitCount.coerceAtLeast(0),
    )
}?.toMap()

/**
 * 카드 줄 '받은 혜택 1,000원 · 확정 전 +500원'(11/14 결정: 히어로처럼 확정 전을 받은 혜택에 섞지 않는다).
 * 원화로 못 바꾼 줄이 있으면 덧붙인다. 응답에 없는 카드(이번 달 YAMU 결제 없음)는 null — 0원으로 지어내지 않는다.
 * 글자를 키워 두 줄이 되면 '·' 자리에서만 끊기게 마디 안은 줄바꿈 없는 공백으로 잇는다('확정 전' 따로 '+6,000원' 따로 갈라지지 않게)
 */
fun CardBenefit?.lineText(): String? {
    this ?: return null
    return listOfNotNull(
        "받은 혜택 ${confirmed.toWon()}원",
        "확정 전 +${pending.toWon()}원".takeIf { pending > 0 },
        "원화로 못 바꾼 혜택 ${unconvertedCount}건".takeIf { unconvertedCount > 0 },
    ).joinToString(" · ") { it.replace(' ', NO_BREAK) }
}

private const val NO_BREAK = ' '

/** GET forecasts/balance — 홈은 기준 계좌 하나 · 30일로 부르고 지금 잔고 · 최저 잔고 · 날짜만 쓴다(FORECAST-02) */
@Serializable
data class HomeForecastDto(
    val currentTotalBalance: Long? = null,
    val summary: HomeForecastSummaryDto? = null,
    val calculatedAt: String? = null,
)

@Serializable
data class HomeForecastSummaryDto(val minBalance: Long? = null, val minBalanceDate: String? = null)

/** 잔고예보 요약 칸 값. [accountLabel]은 계좌 목록을 못 받으면 null(칩을 숨긴다) */
data class HomeForecastBrief(
    val accountId: Long,
    val accountLabel: String?,
    val currentBalance: Long?,
    val minBalance: Long?,
    val minDate: LocalDate?,
    /** '지금 잔고 · ○○ 기준' 시각 — 새 홈 칸은 잔액을 읽은 balanceAsOf, 옛 서버 forecasts/balance는 calculatedAt */
    val asOfIso: String?,
    /** 최저 잔고를 본 기간이 내일부터(새 홈 칸, 30점)인지 — 옛 서버 forecasts/balance는 오늘 포함 31점 */
    val fromTomorrow: Boolean = false,
    /** 생활비 자료가 부족해 어림한 예보(잠정) */
    val provisional: Boolean = false,
)

/**
 * 새 홈 칸 → 요약 값(BE -490). 계좌 칩 이름은 홈 응답에 없어 계좌 목록으로 따로 붙인다(처음엔 null).
 * 기준 계좌를 모르면 null(그 칸만 실패로 본다)
 */
fun HomeForecastPreviewDto.toBrief(): HomeForecastBrief? {
    val id = accountId ?: return null
    return HomeForecastBrief(
        accountId = id,
        accountLabel = null,
        currentBalance = currentBalance,
        minBalance = minBalance,
        minDate = parseDate(minBalanceDate),
        asOfIso = balanceAsOf,
        fromTomorrow = true,
        provisional = isProvisional == true,
    )
}

/** 요약 최저 잔고 줄 이름 — 새 홈 칸은 내일부터 30일, 옛 서버는 오늘 포함 30일 */
val HomeForecastBrief.minLabel: String
    get() = if (fromTomorrow) "내일부터 ${HOME_FORECAST_DAYS}일 중 가장 낮을 때" else "${HOME_FORECAST_DAYS}일 안 가장 낮을 때"

/** 홈 요약 · 잔고예보가 같은 기간을 쓰게 — 30일(11/13 결정: 기준 계좌 하나 · 30일) */
const val HOME_FORECAST_DAYS = 30

/** 계좌 칩 '신한은행 ·0275'(계좌 관리 · 카드 상세와 같은 모양) — 목록에 그 계좌가 없으면 null */
fun List<AccountDto>.labelOf(accountId: Long): String? =
    firstOrNull { it.accountId == accountId }?.let { "${it.bank.name} ·${it.maskedAccountNo.lastDigits()}" }

fun HomeForecastDto.toBrief(accountId: Long, accountLabel: String?) = HomeForecastBrief(
    accountId = accountId,
    accountLabel = accountLabel,
    currentBalance = currentTotalBalance,
    minBalance = summary?.minBalance,
    minDate = parseDate(summary?.minBalanceDate),
    asOfIso = calculatedAt,
)

/**
 * 기준 시각 '오늘 14:05 기준' · '어제 21:10 기준' · '11/10 09:00 기준'. 서버는 UTC에 'Z'를 붙이고, 'Z'가 없으면 UTC로 본다(가짜 데이터).
 * 못 읽으면 null
 */
fun asOfLabel(iso: String?, now: ZonedDateTime = ZonedDateTime.now(KST)): String? {
    if (iso.isNullOrBlank()) return null
    val at = runCatching { OffsetDateTime.parse(iso).atZoneSameInstant(KST) }.getOrNull()
        ?: runCatching { LocalDateTime.parse(iso).atOffset(ZoneOffset.UTC).atZoneSameInstant(KST) }.getOrNull()
        ?: return null
    val hm = "%02d:%02d".format(at.hour, at.minute)
    val today = now.withZoneSameInstant(KST).toLocalDate()
    return when (at.toLocalDate()) {
        today -> "오늘 $hm 기준"
        today.minusDays(1) -> "어제 $hm 기준"
        else -> "${at.monthValue}/${at.dayOfMonth} $hm 기준"
    }
}
