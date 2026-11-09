package com.yamu.app.core.ui

import com.yamu.app.core.format.formatSyncedAt
import com.yamu.app.core.network.ApiError

/** 오류 → 사용자 문장(02 문서 6장). 해요체 · 무엇을 하면 되는지 */
fun ApiError.userMessage(): String = when (this) {
    is ApiError.BankUnavailable -> "은행 연결이 늦어요"
    ApiError.Network -> "인터넷 연결을 확인해 주세요"
    is ApiError.DataInsufficient -> "데이터가 더 모이면 알려 드려요"
    is ApiError.NotFound -> "찾을 수 없어요"
    is ApiError.Unauthorized -> "다시 로그인해 주세요"
    is ApiError.StepUpRequired -> "본인 확인이 필요해요"
    is ApiError.Conflict -> "같은 요청이 이미 처리되고 있어요"
    is ApiError.InvalidRequest -> "입력한 내용을 다시 확인해 주세요"
    is ApiError.Locked -> "잠시 잠겼어요 · 나중에 다시 시도해 주세요"
    is ApiError.Server, is ApiError.Unknown -> "잠시 뒤 다시 시도해 주세요"
}

/** 새로고침이 실패해 마지막 값을 보여 줄 때 한 줄 — '은행 연결이 늦어요 · 마지막 오늘 15:44 반영'(언제 값인지 모르면 '마지막 값을 보여 드려요') */
fun staleLine(message: String, syncedAtIso: String?): String =
    "$message · " + (formatSyncedAt(syncedAtIso)?.let { "마지막 $it" } ?: "마지막 값을 보여 드려요")
