package com.yamu.app.feature.my.data

import android.content.Context
import android.content.ContextWrapper
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.network.errorJson
import com.yamu.app.core.network.json
import com.yamu.app.core.network.retrofit
import com.yamu.app.core.security.SessionStore
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/** 알림 목록 · 배지 · 설정을 BE 모양과 맞춘다 — NotificationController · NotificationSettingsController(BE !228) */
class NotificationContractTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: MyRepositoryImpl

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        repository = MyRepositoryImpl(server.retrofit().create(MyApi::class.java), SessionStore(noFilesContext))
    }

    /** SessionStore는 만들 때 DataStore에 applicationContext만 넘기고 파일은 읽을 때 연다. 알림 함수는 세션을 읽지 않는다 */
    private val noFilesContext = object : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
    }

    @After fun tearDown() = server.close()

    /** NotificationListResponse — unreadCount 아래에 공통 페이지 칸이 펼쳐진다. 시각은 UTC 'Z'(마이크로초까지) */
    private val listJson = """{"success":true,"data":{"unreadCount":3,"items":[
        {"notificationId":12,"type":"BILL_DUE_SOON","title":"출금 예정","body":"내일 30,000원","refType":"CARD_BILL","refId":88,
         "isRead":false,"readAt":null,"createdAt":"2026-11-10T05:30:00.123456Z"},
        {"notificationId":7,"type":"PAYMENT_RESULT","title":"결제 완료","body":"12,000원","refType":"PAYMENT_TRANSACTION","refId":1024,
         "isRead":true,"readAt":"2026-11-09T01:00:00Z","createdAt":"2026-11-08T01:00:00Z"},
        {"notificationId":3,"type":"SECURITY","title":"새 기기 로그인","body":"b","refType":null,"refId":null,
         "isRead":false,"readAt":null,"createdAt":"2026-10-20T01:00:00Z"}],
        "page":1,"size":20,"totalElements":3,"totalPages":1,"hasNext":false},"error":null}"""

    @Test fun `배지 수는 page 1 · size 1로 부르고 unreadCount를 쓴다 — page 0이면 BE가 400`() = runTest {
        server.enqueue(json(200, listJson))

        val r = repository.unreadNotificationCount()

        assertEquals(3, (r as ApiResult.Success).data)
        val request = server.takeRequest()
        assertEquals("/notifications", request.url.encodedPath)
        assertEquals("1", request.url.queryParameter("page"))
        assertEquals("1", request.url.queryParameter("size"))
    }

    @Test fun `배지 요청이 실패하면 실패로 돌려준다 — 화면은 배지를 그대로 둔다`() = runTest {
        server.enqueue(errorJson(400, "INVALID_REQUEST"))

        assertTrue(repository.unreadNotificationCount() is ApiResult.Failure)
    }

    @Test fun `목록 — 안 읽은 수 · 읽음 · 이동 대상 · 시각을 서버 모양 그대로 읽는다`() = runTest {
        server.enqueue(json(200, listJson))

        val page = (repository.notifications(page = 1) as ApiResult.Success).data

        assertEquals(3, page.unread)
        assertFalse(page.hasNext)
        assertEquals(listOf(12L, 7L, 3L), page.items.map { it.id })
        assertEquals(listOf(false, true, false), page.items.map { it.read })
        assertEquals(NotiTarget.Forecast, page.items[0].target)
        assertEquals(NotiTarget.Payment(1024), page.items[1].target)
        assertEquals(NotiTarget.None, page.items[2].target)
        // 'Z' · 마이크로초 시각도 한국 날짜로 묶인다(UTC 05:30 = 한국 14:30)
        assertEquals("오늘", notificationGroup(page.items[0].createdIso, today = LocalDate.of(2026, 11, 10)))
        val request = server.takeRequest()
        assertEquals("1", request.url.queryParameter("page"))
        assertEquals("20", request.url.queryParameter("size"))
    }

    @Test fun `설정 — 앱 모양 응답을 읽고 securityEnabled는 무시한다`() = runTest {
        server.enqueue(
            json(
                200,
                """{"success":true,"data":{"performance":{"enabled":true,"time":"21:00"},
                  "balance":{"enabled":false,"alertThreshold":100000,"time":"21:00"},
                  "payment":{"result":true},"securityEnabled":true},"error":null}""",
            ),
        )

        val s = (repository.notificationSettings() as ApiResult.Success).data

        assertEquals(NotiSettings(performance = true, balance = false, balanceThreshold = 100_000, payment = true, time = "21:00"), s)
        assertEquals("/notifications/settings", server.takeRequest().url.encodedPath)
    }
}
