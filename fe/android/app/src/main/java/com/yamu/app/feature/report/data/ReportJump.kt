package com.yamu.app.feature.report.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** 소비분석(S-01) 안에서 바로 내려갈 칸 */
enum class ReportSection {
    /** 사용금액 바로 아래 자산 흐름 칸(v4 ANALYSIS-01, S15P31E103-483이 채운다) */
    ASSET_FLOW,
}

/**
 * 다른 화면에서 '소비분석의 이 칸으로' 보내는 한 번짜리 요청(S15P31E103-479).
 * 홈 잔고예보 요약의 '자산 흐름 보기'(HOME-15)가 [request]한 뒤 소비분석 탭으로 바꾸면, S-01이 그 칸까지 내려가고 [consume]한다.
 * 탭 첫 화면은 탭을 바꿀 때 저장된 채 되살아나 새 경로 인자를 받지 못해서 경로 인자 대신 이 요청을 쓴다.
 * 자산 흐름은 이번 달 · 오늘부터 보여야 하므로 같이 쓰는 [ReportMonth]를 이번 달로 되돌린다.
 */
@Singleton
class ReportJump @Inject constructor(private val months: ReportMonth) {
    private val _pending = MutableStateFlow<ReportSection?>(null)
    val pending: StateFlow<ReportSection?> = _pending.asStateFlow()

    fun request(section: ReportSection) {
        if (section == ReportSection.ASSET_FLOW) months.reset()
        _pending.value = section
    }

    /** 내려간 뒤(또는 내려갈 칸이 없을 때) — 같은 요청을 다음에 또 쓰지 않게. 그 사이 다른 요청이 왔으면 그건 남긴다 */
    fun consume(section: ReportSection) {
        _pending.compareAndSet(section, null)
    }
}
