package com.yamu.app.feature.report.data

import com.yamu.app.core.format.KST
import java.time.YearMonth
import javax.inject.Inject
import javax.inject.Singleton

/** 고를 수 있는 달 수 — 이번 달 포함 최근 12개월. 다음 달로는 가지 않는다(8.2.77 사용자 결정) */
const val REPORT_MONTHS = 12

/** 화면에 보이는 달과 그때의 이번 달(한국 기준) — 화살표를 켤지 이 둘로 정한다 */
data class ShownMonth(val month: YearMonth, val thisMonth: YearMonth) {
    val first: YearMonth get() = thisMonth.minusMonths(REPORT_MONTHS - 1L)
    val isThisMonth: Boolean get() = month == thisMonth
    val canPrev: Boolean get() = month > first
    val canNext: Boolean get() = month < thisMonth
    val prev: YearMonth get() = month.minusMonths(1)
    val next: YearMonth get() = month.plusMonths(1)
}

/** '11월'(올해) · '2025년 12월'(다른 해) */
fun monthTitle(month: YearMonth, thisMonth: YearMonth): String =
    if (month.year == thisMonth.year) "${month.monthValue}월" else "${month.year}년 ${month.monthValue}월"

/**
 * 소비분석(S-01) · 혜택 리포트(S-04) · 결제 내역(S-05) · CSV 저장이 같이 쓰는 달. 한 화면에서 바꾸면 나머지도 그 달을 본다.
 *
 * 고른 달이 없으면(null) 이번 달을 따라간다 — 앱을 켜 둔 채 달이 바뀌면 다음에 불러올 때 새 달이다.
 * 이번 달을 고르면 null로 둔다. 범위 밖 달은 끝 달로 당긴다.
 * S-01이 새로 만들어질 때(로그인 · 앱 새로 켜기)와 로그아웃 · 이 폰 기기 지움 때(AuthRepositoryImpl) [reset] — 앞사람이 고른 달이 다음 사람에게 남지 않게.
 * 탭 화면은 로그아웃 뒤에도 저장된 채 되살아날 수 있어(S-01 init이 다시 안 돎) 로그아웃 쪽에서도 비운다.
 * 고른 달은 메모리에만 둔다 — 앱이 백그라운드에서 정리됐다 돌아오면 S-04 · S-05도 이번 달로 열린다(CSV 달은 따로 SavedStateHandle).
 */
@Singleton
class ReportMonth(private val thisMonth: () -> YearMonth) {
    @Inject constructor() : this({ YearMonth.now(KST) })

    /** 고른 달 — null이면 이번 달을 따라간다 */
    @Volatile private var picked: YearMonth? = null

    fun current(): ShownMonth {
        val now = thisMonth()
        val month = (picked ?: now).coerceIn(now.minusMonths(REPORT_MONTHS - 1L), now)
        return ShownMonth(month, now)
    }

    fun select(month: YearMonth) {
        val now = thisMonth()
        val m = month.coerceIn(now.minusMonths(REPORT_MONTHS - 1L), now)
        picked = if (m == now) null else m
    }

    fun reset() {
        picked = null
    }
}
