package com.yamu.app.feature.home

import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 홈 칸마다 따로 받는 값(v4 · COMMON-08) — 다시 불러오다 실패해도 마지막 값을 지우지 않는다 */
class HomeSectionTest {

    private val failed = ApiError.Server("INTERNAL_ERROR")

    @Test fun `처음 받으면 Ready`() {
        assertEquals(HomeSection.Ready(3), HomeSection.Loading.next(ApiResult.Success(3)))
    }

    @Test fun `처음부터 실패면 Failed`() {
        assertEquals(HomeSection.Failed(failed), HomeSection.Loading.next(ApiResult.Failure(failed)))
    }

    @Test fun `받은 값이 있으면 실패해도 그 값을 둔다`() {
        assertEquals(HomeSection.Ready(3), HomeSection.Ready(3).next(ApiResult.Failure(failed)))
    }

    @Test fun `실패 뒤 다시 받으면 새 값`() {
        assertEquals(HomeSection.Ready(4), HomeSection.Failed(failed).next(ApiResult.Success(4)))
    }

    @Test fun `값은 Ready에만 있다`() {
        assertEquals(5, HomeSection.Ready(5).dataOrNull)
        assertNull(HomeSection.Loading.dataOrNull)
        assertNull(HomeSection.Failed(failed).dataOrNull)
    }
}
