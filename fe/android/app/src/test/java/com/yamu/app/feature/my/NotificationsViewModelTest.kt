package com.yamu.app.feature.my

import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.feature.my.data.AppNotification
import com.yamu.app.feature.my.data.Device
import com.yamu.app.feature.my.data.MyInfo
import com.yamu.app.feature.my.data.MyRepository
import com.yamu.app.feature.my.data.NotiKind
import com.yamu.app.feature.my.data.NotiSettings
import com.yamu.app.feature.my.data.NotiTarget
import com.yamu.app.feature.my.data.NotificationsPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 알림 목록 '더 보기'(-497) — 다음 쪽 붙이기, 쪽이 밀려 같은 알림이 다시 올 때, 새로고침이 읽은 쪽까지 다시 받기, 실패하면 보던 목록을 두기
 */
class NotificationsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repo = PagedRepo()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `처음엔 1쪽만 받고 다음 쪽이 있으면 더 보기로 붙인다`() = runTest(dispatcher) {
        repo.all = (1L..5L).map(::noti).reversed()
        val vm = NotificationsViewModel(repo)
        vm.refresh()
        advanceUntilIdle()
        assertEquals(listOf(5L, 4L), vm.state.value.items.map { it.id })
        assertTrue(vm.state.value.hasNext)
        vm.loadMore()
        advanceUntilIdle()
        assertEquals(listOf(5L, 4L, 3L, 2L), vm.state.value.items.map { it.id })
        vm.loadMore()
        advanceUntilIdle()
        assertEquals(listOf(5L, 4L, 3L, 2L, 1L), vm.state.value.items.map { it.id })
        assertFalse(vm.state.value.hasNext)
        assertEquals(listOf(1, 2, 3), repo.pages)
        vm.loadMore()   // 마지막 쪽 — 보내지 않는다
        advanceUntilIdle()
        assertEquals(3, repo.pages.size)
    }

    @Test fun `연타하면 한 번만 보낸다`() = runTest(dispatcher) {
        repo.all = (1L..5L).map(::noti).reversed()
        val vm = NotificationsViewModel(repo)
        vm.refresh()
        advanceUntilIdle()
        vm.loadMore()
        vm.loadMore()
        runCurrent()
        assertTrue(vm.state.value.loadingMore)
        advanceUntilIdle()
        assertEquals(listOf(1, 2), repo.pages)
        assertFalse(vm.state.value.loadingMore)
    }

    @Test fun `쪽 사이에 새 알림이 오면 밀려 다시 온 알림은 한 번만 — 먼저 받은 줄(읽음 표시)을 남긴다`() = runTest(dispatcher) {
        repo.all = (1L..4L).map(::noti).reversed()
        val vm = NotificationsViewModel(repo)
        vm.refresh()
        advanceUntilIdle()
        vm.open(vm.state.value.items[1])   // 3번을 읽음
        repo.all = listOf(noti(6)) + repo.all   // 새 알림 — 2쪽에 3번이 다시 온다
        vm.loadMore()
        advanceUntilIdle()
        assertEquals(listOf(4L, 3L, 2L), vm.state.value.items.map { it.id })
        assertTrue(vm.state.value.items.first { it.id == 3L }.read)
    }

    @Test fun `돌아와 새로고치면 읽은 쪽까지 1쪽부터 다시 받는다`() = runTest(dispatcher) {
        repo.all = (1L..6L).map(::noti).reversed()
        val vm = NotificationsViewModel(repo)
        vm.refresh()
        advanceUntilIdle()
        vm.loadMore()
        advanceUntilIdle()
        repo.pages.clear()
        repo.all = listOf(noti(7)) + repo.all
        vm.refresh()
        advanceUntilIdle()
        assertEquals(listOf(1, 2), repo.pages)
        assertEquals(listOf(7L, 6L, 5L, 4L), vm.state.value.items.map { it.id })
        assertEquals(2, vm.state.value.page)
    }

    @Test fun `새로고침이 시작되면 도는 더 보기는 버린다`() = runTest(dispatcher) {
        repo.all = (1L..6L).map(::noti).reversed()
        val vm = NotificationsViewModel(repo)
        vm.refresh()
        advanceUntilIdle()
        vm.loadMore()
        runCurrent()
        vm.refresh()
        advanceUntilIdle()
        assertEquals(listOf(6L, 5L), vm.state.value.items.map { it.id })   // 2쪽이 섞이지 않는다
        assertEquals(1, vm.state.value.page)
        assertFalse(vm.state.value.loadingMore)
    }

    @Test fun `더 보기가 실패하면 보던 목록을 두고 다시 누를 수 있다`() = runTest(dispatcher) {
        repo.all = (1L..4L).map(::noti).reversed()
        val vm = NotificationsViewModel(repo)
        vm.refresh()
        advanceUntilIdle()
        repo.failPage = 2
        vm.loadMore()
        advanceUntilIdle()
        assertEquals(listOf(4L, 3L), vm.state.value.items.map { it.id })
        assertEquals(1, vm.state.value.page)
        assertTrue(vm.state.value.hasNext)
        assertNotNull(vm.state.value.moreError)
        assertFalse(vm.state.value.loadingMore)
        repo.failPage = null
        vm.loadMore()
        advanceUntilIdle()
        assertNull(vm.state.value.moreError)
        assertEquals(listOf(4L, 3L, 2L, 1L), vm.state.value.items.map { it.id })
    }

    @Test fun `새로고침 중 2쪽이 실패하면 보던 목록을 그대로 둔다`() = runTest(dispatcher) {
        repo.all = (1L..4L).map(::noti).reversed()
        val vm = NotificationsViewModel(repo)
        vm.refresh()
        advanceUntilIdle()
        vm.loadMore()
        advanceUntilIdle()
        repo.all = listOf(noti(5)) + repo.all
        repo.failPage = 2
        vm.refresh()
        advanceUntilIdle()
        assertEquals(listOf(4L, 3L, 2L, 1L), vm.state.value.items.map { it.id })
        assertEquals(2, vm.state.value.page)
        assertFalse(vm.state.value.loading)
    }

    // ── 모두 읽음과 쪽 받기가 겹칠 때(!327 리뷰) ──

    @Test fun `모두 읽음을 보내는 동안 더 보기는 받지 않고, 실패하면 읽음 표시만 되돌린다`() = runTest(dispatcher) {
        repo.all = (1L..4L).map(::noti).reversed()
        val vm = NotificationsViewModel(repo)
        vm.refresh()
        advanceUntilIdle()
        repo.markAllFails = true
        var failed: String? = null
        vm.readAll { failed = it }
        runCurrent()
        assertEquals(0, vm.state.value.unread)
        vm.loadMore()   // 모두 읽음 중 — 보내지 않는다
        advanceUntilIdle()
        assertEquals(listOf(1), repo.pages)
        assertNotNull(failed)
        assertEquals(4, vm.state.value.unread)                       // 안 읽은 수와
        assertTrue(vm.state.value.items.none { it.read })            // 읽음 표시만 되돌리고
        assertEquals(1, vm.state.value.page)                         // 목록 · 쪽은 그대로
        assertTrue(vm.state.value.hasNext)
        vm.loadMore()   // 끝났으니 이제 받는다
        advanceUntilIdle()
        assertEquals(listOf(4L, 3L, 2L, 1L), vm.state.value.items.map { it.id })
        assertTrue(vm.state.value.items.none { it.read })
    }

    @Test fun `더 보기 응답을 기다리는 중 모두 읽음이 성공하면 옛 응답이 안 읽은 수를 되돌리지 않는다`() = runTest(dispatcher) {
        repo.all = (1L..4L).map(::noti).reversed()
        val vm = NotificationsViewModel(repo)
        vm.refresh()
        advanceUntilIdle()
        vm.loadMore()
        runCurrent()                                  // 2쪽 요청이 나갔다(응답 unread=4 예정)
        vm.readAll { error("실패하면 안 된다") }       // 그 사이 모두 읽음
        advanceUntilIdle()
        assertEquals(1, repo.markAllCalls)
        assertEquals(0, vm.state.value.unread)
        assertTrue(vm.state.value.items.all { it.read })
        assertFalse(vm.state.value.loadingMore)
        assertEquals(1, vm.state.value.page)          // 끊긴 더 보기는 다시 누르면 된다
        vm.loadMore()
        advanceUntilIdle()
        assertEquals(0, vm.state.value.unread)
        assertEquals(4, vm.state.value.items.size)
        assertTrue(vm.state.value.items.all { it.read })
    }

    @Test fun `모두 읽음 중 화면에 돌아오면 새로고침을 미뤘다가 끝난 뒤 한 번 받는다`() = runTest(dispatcher) {
        repo.all = (1L..2L).map(::noti).reversed()
        val vm = NotificationsViewModel(repo)
        vm.refresh()
        advanceUntilIdle()
        vm.readAll { }
        runCurrent()
        vm.refresh()
        vm.refresh()
        runCurrent()
        assertEquals(listOf(1), repo.pages)           // 모두 읽음 중엔 보내지 않는다
        advanceUntilIdle()
        assertEquals(listOf(1, 1), repo.pages)        // 끝나고 한 번
        assertEquals(0, vm.state.value.unread)
    }

    private fun noti(id: Long) = AppNotification(id, "알림 $id", null, read = false, createdIso = "2026-11-15T09:00:00+09:00", target = NotiTarget.None)

    /** 2개씩 나누는 가짜 저장소 — 서버처럼 최신순 목록을 쪽 번호로 자른다 */
    private class PagedRepo : MyRepository {
        var all: List<AppNotification> = emptyList()
        val pages = mutableListOf<Int>()
        var failPage: Int? = null

        override suspend fun notifications(page: Int): ApiResult<NotificationsPage> {
            pages += page
            delay(100)
            if (page == failPage) return ApiResult.Failure(ApiError.Network)
            val from = (page - 1) * SIZE
            return ApiResult.Success(NotificationsPage(all.count { !it.read }, all.drop(from).take(SIZE), hasNext = all.size > from + SIZE))
        }

        var markAllFails = false
        var markAllCalls = 0

        override suspend fun markRead(id: Long): ApiResult<Unit> = ApiResult.Success(Unit)

        /** 서버처럼 성공하면 전부 읽음 — 300ms 걸린다 */
        override suspend fun markAllRead(): ApiResult<Unit> {
            markAllCalls++
            delay(300)
            if (markAllFails) return ApiResult.Failure(ApiError.Network)
            all = all.map { it.copy(read = true) }
            return ApiResult.Success(Unit)
        }
        override suspend fun me(): ApiResult<MyInfo> = error("안 씀")
        override suspend fun setAutoSelect(on: Boolean): ApiResult<Unit> = error("안 씀")
        override suspend fun setAutoLock(seconds: Int): ApiResult<Unit> = error("안 씀")
        override suspend fun setPinOnlyForCode(pinOnly: Boolean): ApiResult<Unit> = error("안 씀")
        override suspend fun notificationSettings(): ApiResult<NotiSettings> = error("안 씀")
        override suspend fun setNotification(kind: NotiKind, enabled: Boolean): ApiResult<Unit> = error("안 씀")
        override suspend fun setNotificationTime(hhmm: String): ApiResult<Unit> = error("안 씀")
        override suspend fun devices(): ApiResult<List<Device>> = error("안 씀")
        override suspend fun revokeDevice(id: Long, stepUpToken: String): ApiResult<Unit> = error("안 씀")
        override suspend fun withdraw(stepUpToken: String): ApiResult<Unit> = error("안 씀")

        companion object {
            const val SIZE = 2
        }
    }
}
