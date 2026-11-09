package com.yamu.app.feature.my.data

import com.yamu.app.core.network.YamuJson
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** 마이 · 알림 — 서버 값 정리 · 표기(13 문서 9·10-1 · 9·10-3) */
class MyMapperTest {
    private val today = LocalDate.parse("2026-10-27")

    @Test fun `내 정보 — v0_3_1 설정이 없으면 기본값(1분 · 지문 또는 간편 비밀번호 · 자동 선택 켜짐)`() {
        // GET /users/me(BE !113) 응답 모양 그대로 — 모르는 칸(onboarding · createdAt · pinSet …)은 버린다
        val full = YamuJson.decodeFromString(
            UserInfoDto.serializer(),
            """{"userId":1,"maskedPhone":"010-****-5678","name":"김야무",
               "onboarding":{"cardRegistered":true,"primaryAccountSet":true,"completed":true},
               "securitySettings":{"autoLockSeconds":300,"setupGraceSeconds":300,"barcodeAuthGraceSeconds":60,"barcodeAuthMethod":"PIN_ONLY",
                 "biometricEnabled":true,"pinSet":true,"currentDevice":{"userDeviceId":7,"deviceName":"Galaxy S24"}},
               "paymentSettings":{"autoSelect":false,"defaultUserCardId":12},"createdAt":"2026-11-01T00:00:00Z"}""",
        ).toMyInfo()
        assertEquals("김야무", full.name)
        assertEquals("010-****-5678", full.maskedPhone)
        assertEquals(300, full.autoLockSeconds)
        assertTrue(full.pinOnlyForCode)
        assertTrue(full.biometricEnabled)
        assertEquals(7L, full.currentDeviceId)
        assertFalse(full.autoSelect)

        val bare = YamuJson.decodeFromString(UserInfoDto.serializer(), """{"userId":1}""").toMyInfo()
        assertEquals(60, bare.autoLockSeconds)
        assertFalse(bare.pinOnlyForCode)
        assertTrue(bare.autoSelect)
        assertNull(bare.currentDeviceId)
        assertNull(bare.maskedPhone)
    }

    @Test fun `알림 설정 — 받는 시각은 실적 · 잔액 공통, 없으면 오전 8시 30분`() {
        val s = YamuJson.decodeFromString(
            NotificationSettingsDto.serializer(),
            """{"performance":{"enabled":false,"time":"09:00"},"balance":{"enabled":true,"alertThreshold":100000},"payment":{"result":true},"security":{"enabled":true}}""",
        ).toSettings()
        assertFalse(s.performance)
        assertTrue(s.balance)
        assertEquals(100_000L, s.balanceThreshold)
        assertTrue(s.payment)
        assertEquals("09:00", s.time)
        assertEquals("08:30", YamuJson.decodeFromString(NotificationSettingsDto.serializer(), "{}").toSettings().time)
    }

    @Test fun `알림 설정 바꾸기 — 바꾼 칸만 보낸다(보안은 없음)`() {
        val perf = notificationToggleBody(NotiKind.PERFORMANCE, false)
        assertEquals(setOf("performance"), perf.keys)
        assertEquals("false", perf["performance"]!!.jsonObject["enabled"]!!.jsonPrimitive.content)
        val pay = notificationToggleBody(NotiKind.PAYMENT, true)
        assertEquals("true", pay["payment"]!!.jsonObject["result"]!!.jsonPrimitive.content)
        val time = notificationTimeBody("07:00")
        assertEquals(setOf("performance", "balance"), time.keys)
        assertEquals("07:00", time["balance"]!!.jsonObject["time"]!!.jsonPrimitive.content)
    }

    @Test fun `시각 표기`() {
        assertEquals("오전 8:30", koreanTime("08:30"))
        assertEquals("오후 12:05", koreanTime("12:05"))
        assertEquals("오전 12:00", koreanTime("00:00"))
        assertEquals("오후 9:15", koreanTime("21:15"))
    }

    @Test fun `알림 누르면 갈 곳 — refType`() {
        fun target(refType: String?, refId: Long? = 5) =
            NotificationDto(1, title = "t", refType = refType, refId = refId).toNotification().target
        assertEquals(NotiTarget.Card(5), target("USER_CARD"))
        assertEquals(NotiTarget.Payment(5), target("payment_transaction"))
        assertEquals(NotiTarget.Forecast, target("CARD_BILL"))
        assertEquals(NotiTarget.None, target("USER_CARD", refId = null))
        assertEquals(NotiTarget.None, target("LOGIN"))
        assertEquals(NotiTarget.None, target(null))
    }

    @Test fun `읽음 — isRead가 없으면 readAt으로`() {
        assertTrue(NotificationDto(1, title = "t", readAt = "2026-10-27T08:31:00+09:00").toNotification().read)
        assertFalse(NotificationDto(1, title = "t").toNotification().read)
        assertFalse(NotificationDto(1, title = "t", isRead = false, readAt = "x").toNotification().read)
    }

    @Test fun `알림 묶음 · 시각 — 오늘 · 이번 주(7일 안) · 이전`() {
        assertEquals("오늘", notificationGroup("2026-10-27T08:30:00+09:00", today))
        assertEquals("오늘", notificationGroup("2026-10-26T23:30:00Z", today))         // KST로 27일 08:30
        assertEquals("이번 주", notificationGroup("2026-10-21T08:30:00+09:00", today))
        assertEquals("이전", notificationGroup("2026-10-20T08:30:00+09:00", today))
        assertEquals("이전", notificationGroup(null, today))

        assertEquals("08:30", notificationTime("2026-10-27T08:30:00+09:00", today))
        assertEquals("어제 12:30", notificationTime("2026-10-26T12:30:00+09:00", today))
        assertEquals("10/14 (수) 08:30", notificationTime("2026-10-14T08:30:00+09:00", today))
        assertNull(notificationTime("not-a-date", today))
    }

    @Test fun `다른 기기 · 자동 잠금 표기`() {
        assertEquals("10월 12일 (월) 21:14 접속", deviceLastUsed("2026-10-12T12:14:00Z"))
        assertNull(deviceLastUsed(null))
        assertEquals("바로", autoLockLabel(0))
        assertEquals("1분", autoLockLabel(60))
        assertEquals("5분", autoLockLabel(300))
        assertEquals("30초", autoLockLabel(30))
        assertEquals("10분", autoLockLabel(600))
    }
}
