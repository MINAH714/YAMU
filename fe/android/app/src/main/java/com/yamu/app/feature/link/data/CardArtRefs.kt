package com.yamu.app.feature.link.data

import com.yamu.app.core.network.ApiResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 카드 그림을 찾는 값 — 실적 · 한도 · 청구서 · 거래 상세 응답에는 상품명이 없고 별명(없으면 상품명)만 온다.
 * 별명 카드도 그림이 붙도록 카드 목록(GET /api/cards, 금융망을 부르지 않는다)에서 userCardId로 상품명 · 카드사 · 정지 여부를 찾는다(-438).
 * [dimmed]는 카드사 정지 · 만료 — 그림을 흐리게 그린다
 */
data class CardArtRef(val brand: Brand, val productName: String, val dimmed: Boolean)

fun List<LinkedCard>.toArtRefs(): Map<Long, CardArtRef> =
    associate { it.userCardId to CardArtRef(it.brand, it.productName, dimmed = it.issuerBlocked) }

/** 카드 목록을 못 받으면 빈 표 — 화면은 별명으로 그림을 찾고, 못 찾으면 카드사 색 카드로 보인다 */
suspend fun LinkRepository.cardArtRefs(): Map<Long, CardArtRef> = when (val r = cards()) {
    is ApiResult.Success -> r.data.toArtRefs()
    is ApiResult.Failure -> emptyMap()
}

/**
 * 화면 하나의 그림 값 — 처음 한 번 받고, 그 뒤엔 모르는 카드가 보일 때만(그 사이 등록한 카드) 다시 받는다.
 * 받는 중이면 겹쳐 보내지 않고, 못 받으면 알던 값을 지우지 않는다
 */
class CardArtRefsLoader(private val link: LinkRepository, private val scope: CoroutineScope) {
    private val _refs = MutableStateFlow<Map<Long, CardArtRef>>(emptyMap())
    val refs: StateFlow<Map<Long, CardArtRef>> = _refs.asStateFlow()
    private var job: Job? = null
    private var tried = false

    /** [userCardIds] 중 모르는 카드가 있으면(처음엔 늘) 카드 목록을 받는다 */
    fun ensure(userCardIds: Collection<Long> = emptyList()) {
        if (job?.isActive == true) return
        if (tried && userCardIds.all { it in _refs.value }) return
        tried = true
        job = scope.launch {
            val fresh = link.cardArtRefs()
            if (fresh.isNotEmpty()) _refs.value = fresh
        }
    }
}
