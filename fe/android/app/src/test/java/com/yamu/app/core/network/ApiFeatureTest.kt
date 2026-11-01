package com.yamu.app.core.network

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

    @Test fun `release처럼 전부 적으면 전부 진짜`() {
        val all = ApiFeature.entries.joinToString(",") { it.key }
        assertEquals(ApiFeature.entries.toSet(), ApiFeature.parse(all))
    }
}
