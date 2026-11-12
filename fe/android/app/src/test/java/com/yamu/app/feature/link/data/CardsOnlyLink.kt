package com.yamu.app.feature.link.data

import com.yamu.app.core.network.ApiResult
import java.lang.reflect.Proxy

/** 카드 목록(GET /api/cards)만 답하는 연결 저장소 — 화면 테스트의 카드 그림 값용. 다른 메서드는 부르면 실패한다 */
class CardsOnlyLink(var result: ApiResult<List<LinkedCard>> = ApiResult.Success(emptyList())) : LinkRepository by unusedLink() {
    var calls = 0

    override suspend fun cards(): ApiResult<List<LinkedCard>> {
        calls++
        return result
    }

    private companion object {
        fun unusedLink(): LinkRepository = Proxy.newProxyInstance(
            LinkRepository::class.java.classLoader,
            arrayOf(LinkRepository::class.java),
        ) { _, method, _ -> error("안 쓰는 메서드: ${method.name}") } as LinkRepository
    }
}
