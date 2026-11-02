package com.yamu.app.feature.auth.data

import com.yamu.app.core.network.ApiError
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 기기 등록이 실패했을 때 지문 키를 지워도 되나 — 결과 불명이면 남긴다 */
class DeviceKeyFailureTest {

    @Test
    fun `시간 초과 · 연결 끊김은 서버가 등록을 끝냈을 수 있어 키를 남긴다`() {
        assertFalse(ApiError.Network.keyNotOnServer())
    }

    @Test
    fun `서버가 거절한 응답이면 지운다`() {
        assertTrue(ApiError.InvalidRequest("INVALID_PUBLIC_KEY").keyNotOnServer())
        assertTrue(ApiError.Server("INTERNAL_ERROR").keyNotOnServer())
    }
}
