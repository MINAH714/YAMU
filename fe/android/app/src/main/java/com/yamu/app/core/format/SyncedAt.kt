package com.yamu.app.core.format

import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

val KST: ZoneId = ZoneId.of("Asia/Seoul")

private val HM = DateTimeFormatter.ofPattern("HH:mm")

/** 서버 기준 시각(ISO-8601) → '오늘 09:38 반영' · '어제 21:10 반영' · '10월 20일 반영'. 못 읽으면 null */
fun formatSyncedAt(iso: String?, now: ZonedDateTime = ZonedDateTime.now(KST)): String? {
    if (iso.isNullOrBlank()) return null
    val at = try {
        OffsetDateTime.parse(iso).atZoneSameInstant(KST)
    } catch (e: DateTimeParseException) {
        return null
    }
    val today = now.withZoneSameInstant(KST).toLocalDate()
    return when (at.toLocalDate()) {
        today -> "오늘 ${at.format(HM)} 반영"
        today.minusDays(1) -> "어제 ${at.format(HM)} 반영"
        else -> "${at.monthValue}월 ${at.dayOfMonth}일 반영"
    }
}

/** '2026-10-29' → LocalDate, 못 읽으면 null */
fun parseDate(value: String?): LocalDate? = value?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

/** 10/29 */
fun LocalDate.toMonthDay(): String = "$monthValue/$dayOfMonth"

/** 잔액 기준 시각 '2026-10-26T09:52:00+09:00' → '10월 26일 09:52 기준'(C-04). 못 읽으면 null */
fun formatAsOf(iso: String?): String? {
    if (iso.isNullOrBlank()) return null
    val at = runCatching { OffsetDateTime.parse(iso).atZoneSameInstant(KST) }.getOrNull() ?: return null
    return "${at.monthValue}월 ${at.dayOfMonth}일 ${at.format(HM)} 기준"
}

/** 받침이 있으면 '을', 없으면 '를' — '트래블 마일 신용카드를' */
fun String.objectParticle(): String {
    val last = trimEnd().lastOrNull() ?: return "를"
    if (last !in '가'..'힣') return "를"
    return if ((last - '가') % 28 != 0) "을" else "를"
}

/** 받침이 있으면 '으로', 없거나 ㄹ 받침이면 '로' — '신한카드로' · 'KB국민 적립으로' */
fun String.directionParticle(): String {
    val last = trimEnd().lastOrNull() ?: return "로"
    if (last !in '가'..'힣') return "로"
    val jong = (last - '가') % 28
    return if (jong == 0 || jong == 8) "로" else "으로"
}

/**
 * 버튼 · 링크 속 카드 이름 — [max]자를 넘으면 앞에서 자르고 '…'. 버튼은 한 줄 말줄임이라 긴 이름이 뒤 문구('실적 자세히 보기')를 잘라 먹지 않게 이름만 줄인다.
 * 조사는 줄이기 전 이름으로 붙인다
 */
fun String.clipName(max: Int = 8): String = if (length <= max) this else take(max - 1).trimEnd() + "…"

/** '10/20 (화)' */
fun LocalDate.toMonthDayWeek(): String =
    "$monthValue/$dayOfMonth (${dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.KOREAN)})"

/** '11월 10일' */
fun LocalDate.toKoreanDate(): String = "${monthValue}월 ${dayOfMonth}일"
