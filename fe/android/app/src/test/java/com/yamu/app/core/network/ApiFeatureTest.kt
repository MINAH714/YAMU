package com.yamu.app.core.network

import com.yamu.app.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** yamu.realApi(BuildConfig.REAL_API) → 진짜 서버를 쓰는 기능 */
class ApiFeatureTest {
    @Test fun `비어 있으면 전부 가짜`() {
        assertTrue(ApiFeature.parse("").isEmpty())
    }

    @Test fun `쉼표로 적은 기능만 진짜 · 앞뒤 공백은 무시`() {
        assertEquals(setOf(ApiFeature.CASHFLOW, ApiFeature.LINK), ApiFeature.parse("cashflow, link"))
    }

    // 한쪽만 고치면 debug에서 그 이름을 못 켜고, release REAL_API에서도 빠져 isReal 분기가 꺼진다
    @Test fun `build gradle의 기능 목록과 ApiFeature 이름이 같다`() {
        assertEquals(BuildConfig.API_FEATURES.split(',').toSet(), ApiFeature.entries.map { it.key }.toSet())
    }
}
