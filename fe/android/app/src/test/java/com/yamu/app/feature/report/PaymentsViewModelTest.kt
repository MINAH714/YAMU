package com.yamu.app.feature.report

import androidx.lifecycle.SavedStateHandle
import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.feature.payment.data.PaymentEdits
import com.yamu.app.feature.report.data.BenefitReport
import com.yamu.app.feature.report.data.CsvDocuments
import com.yamu.app.feature.report.data.CsvFile
import com.yamu.app.feature.report.data.MonthlySpend
import com.yamu.app.feature.report.data.defaultCsvName
import com.yamu.app.feature.report.data.PaymentItem
import com.yamu.app.feature.report.data.PaymentsPage
import com.yamu.app.feature.report.data.ReportMonth
import com.yamu.app.feature.report.data.ReportRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.YearMonth

/** S-05 — 취소 · 메모 뒤 돌아오면 첫 쪽부터, CSV는 저장 창에서 고른 뒤에 받는다, 달을 바꾸면 그 달 첫 쪽부터 */
class PaymentsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repo = StubReports()
    private val edits = PaymentEdits()
    private val docs = StubDocuments()
    private val months = ReportMonth { NOV }

    private fun vm(saved: SavedStateHandle = SavedStateHandle()) = PaymentsViewModel(saved, repo, edits, docs, months)

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `그대로 돌아오면 펼친 쪽을 두고 첫 쪽만 합친다`() = runTest(dispatcher) {
        val vm = vm()
        vm.refresh(); advanceUntilIdle()
        vm.loadMore(); advanceUntilIdle()
        assertEquals(40, vm.state.value.items.size)
        vm.refresh(); advanceUntilIdle()
        assertEquals(40, vm.state.value.items.size)
        assertEquals(2, vm.state.value.page)
    }

    @Test fun `그사이 취소 · 메모가 있었으면 첫 쪽부터 다시 — 뒤쪽 줄이 옛 상태로 남지 않게`() = runTest(dispatcher) {
        val vm = vm()
        vm.refresh(); advanceUntilIdle()
        vm.loadMore(); advanceUntilIdle()
        repo.canceledIds += 25L      // 둘째 쪽 결제를 상세에서 취소
        edits.changed()
        vm.refresh(); advanceUntilIdle()
        val s = vm.state.value
        assertEquals(20, s.items.size)
        assertEquals(1, s.page)
        assertTrue(s.hasNext)
        vm.loadMore(); advanceUntilIdle()
        assertTrue(vm.state.value.items.first { it.paymentId == 25L }.canceled)
        vm.refresh(); advanceUntilIdle()
        assertEquals(40, vm.state.value.items.size)   // 바뀐 게 없으면 다시 그대로 둔다
    }

    @Test fun `달을 바꾸면 펼친 쪽 · 쪽 번호 · 합계를 버리고 그 달 첫 쪽부터`() = runTest(dispatcher) {
        val vm = vm()
        vm.refresh(); advanceUntilIdle()
        vm.loadMore(); advanceUntilIdle()
        assertEquals(2, vm.state.value.page)

        vm.select(OCT)
        val switching = vm.state.value
        assertEquals(OCT, switching.shown.month)
        assertTrue(switching.items.isEmpty())   // 11월 줄을 10월 머리 아래 두지 않는다
        assertEquals(0, switching.count)
        assertTrue(switching.loading)
        advanceUntilIdle()

        val s = vm.state.value
        assertEquals(1, s.page)
        assertEquals(20, s.items.size)
        assertTrue(s.items.all { it.date?.let(YearMonth::from) == OCT })
        assertEquals("10월", s.periodLabel)   // 끝난 달은 기간 없이
        vm.loadMore(); advanceUntilIdle()
        assertEquals(listOf(1 to NOV, 2 to NOV, 1 to OCT, 2 to OCT), repo.calls)   // 더 보기도 10월 둘째 쪽
    }

    @Test fun `달을 빠르게 넘기면 늦게 온 앞 달 응답이 덮어쓰지 않는다`() = runTest(dispatcher) {
        repo.delays[OCT] = 1_000
        repo.delays[SEP] = 100
        val vm = vm()
        vm.select(OCT)
        advanceTimeBy(10)   // 10월 요청이 나가 있는 동안
        vm.select(SEP)
        advanceUntilIdle()
        val s = vm.state.value
        assertEquals(SEP, s.shown.month)
        assertTrue(s.items.all { it.date?.let(YearMonth::from) == SEP })
        assertEquals(1, s.page)
        assertEquals(listOf(1 to OCT, 1 to SEP), repo.calls)
    }

    @Test fun `앞 달 더 보기 응답이 달을 넘긴 뒤 와도 새 달 목록에 붙지 않는다`() = runTest(dispatcher) {
        val vm = vm()
        vm.refresh(); advanceUntilIdle()
        repo.delays[NOV] = 1_000
        vm.loadMore()                 // 11월 둘째 쪽 — 느리다
        advanceTimeBy(10)
        vm.select(OCT)
        advanceUntilIdle()
        val s = vm.state.value
        assertEquals(20, s.items.size)
        assertTrue(s.items.all { it.date?.let(YearMonth::from) == OCT })
        assertFalse(s.loadingMore)
        assertEquals(1, s.page)
    }

    @Test fun `더 보기 연타는 한 번만 부른다`() = runTest(dispatcher) {
        val vm = vm()
        vm.refresh(); advanceUntilIdle()
        vm.loadMore()
        vm.loadMore()
        advanceUntilIdle()
        assertEquals(listOf(1 to NOV, 2 to NOV), repo.calls)
    }

    @Test fun `다음 달로는 가지 않는다`() = runTest(dispatcher) {
        val vm = vm()
        vm.refresh(); advanceUntilIdle()
        assertFalse(vm.state.value.shown.canNext)
        vm.select(NOV.plusMonths(1))
        advanceUntilIdle()
        assertEquals(NOV, vm.state.value.shown.month)
    }

    @Test fun `CSV — 저장 창을 먼저 띄우고 고른 곳에 받아 쓴다, 연타해도 한 번`() = runTest(dispatcher) {
        val vm = vm()
        vm.requestCsv()
        assertEquals(defaultCsvName(NOV), vm.state.value.saveAs)   // 보이는 달(이번 달) 이름
        assertEquals(0, repo.exports)                              // 고르기 전에는 받지 않는다
        vm.saveAsShown()
        assertNull(vm.state.value.saveAs)
        vm.saveCsvTo("content://doc/1")
        vm.saveCsvTo("content://doc/1")   // 연타
        assertTrue(vm.state.value.exporting)
        vm.requestCsv()                   // 받는 중에는 창을 다시 띄우지 않는다
        assertNull(vm.state.value.saveAs)
        advanceUntilIdle()
        assertEquals(1, repo.exports)
        assertSame(repo.file.bytes, docs.written["content://doc/1"])
        assertEquals("CSV 파일을 저장했어요", vm.state.value.message)
        assertFalse(vm.state.value.exporting)
    }

    @Test fun `CSV — 저장 창을 띄울 때의 달을 받을 때까지 넘긴다`() = runTest(dispatcher) {
        val saved = SavedStateHandle()
        val vm = vm(saved)
        vm.requestCsv()
        val month = YearMonth.parse(saved.get<String>(PaymentsViewModel.CSV_MONTH)!!)
        assertEquals(defaultCsvName(month), vm.state.value.saveAs)   // 이름과 같은 달
        vm.saveAsShown()
        vm.saveCsvTo("content://doc/1"); advanceUntilIdle()
        assertEquals(listOf(month), repo.exportMonths)
        assertNull(saved.get<String>(PaymentsViewModel.CSV_MONTH))   // 쓰고 지운다
    }

    @Test fun `CSV — 보이는 달(10월)로 이름과 내용을 맞춘다, 창이 열린 사이 달을 넘겨도`() = runTest(dispatcher) {
        val saved = SavedStateHandle()
        val vm = vm(saved)
        vm.select(OCT); advanceUntilIdle()
        vm.requestCsv()
        assertEquals("yamu-payments-2026-10.csv", vm.state.value.saveAs)
        vm.saveAsShown()
        vm.select(SEP); advanceUntilIdle()   // 창이 열린 사이 다른 달로
        vm.saveCsvTo("content://doc/1"); advanceUntilIdle()
        assertEquals(listOf(OCT), repo.exportMonths)
    }

    @Test fun `CSV — 10월 말일 자정 직전에 창을 띄우고 11월에 골라도 10월로 받는다(앱이 정리됐다 돌아와도)`() = runTest(dispatcher) {
        // 창이 열린 사이 앱이 정리되면 뷰모델은 새로 만들어지고 SavedStateHandle만 남는다
        val restored = SavedStateHandle(mapOf(PaymentsViewModel.CSV_MONTH to "2026-10"))
        val vm = vm(restored)
        vm.saveCsvTo("content://doc/1"); advanceUntilIdle()
        assertEquals(listOf(YearMonth.of(2026, 10)), repo.exportMonths)
    }

    @Test fun `저장 창을 닫으면 받지 않는다 — 분당 3번 한도를 쓰지 않게`() = runTest(dispatcher) {
        val vm = vm()
        vm.requestCsv(); vm.saveAsShown()
        vm.saveCsvTo(null)
        advanceUntilIdle()
        assertEquals(0, repo.exports)
        assertNull(vm.state.value.message)
    }

    @Test fun `분당 3번 넘음 — 창이 만든 빈 파일을 지우고 남은 초를 알린다`() = runTest(dispatcher) {
        repo.exportResult = ApiResult.Failure(ApiError.from(429, "RATE_LIMITED", buildJsonObject { put("retryAfterSeconds", 42) }))
        val vm = vm()
        vm.saveCsvTo("content://doc/2"); advanceUntilIdle()
        assertEquals(listOf("content://doc/2"), docs.discarded)
        assertFalse(vm.state.value.exporting)
        assertEquals("내려받기는 1분에 3번까지예요. 42초 뒤 다시 눌러 주세요", vm.state.value.message)
    }

    @Test fun `파일을 못 쓰면 빈 파일을 지우고 다시 저장하라고 알린다`() = runTest(dispatcher) {
        docs.writeOk = false
        val vm = vm()
        vm.saveCsvTo("content://doc/3"); advanceUntilIdle()
        assertEquals(listOf("content://doc/3"), docs.discarded)
        assertEquals("파일을 저장하지 못했어요. 다시 저장해 주세요", vm.state.value.message)
    }

    private class StubDocuments : CsvDocuments {
        var writeOk = true
        val written = mutableMapOf<String, ByteArray>()
        val discarded = mutableListOf<String>()

        override suspend fun write(uri: String, bytes: ByteArray): Boolean {
            delay(50)
            if (writeOk) written[uri] = bytes
            return writeOk
        }

        override suspend fun discardIfEmpty(uri: String) {
            discarded += uri
        }
    }

    private class StubReports : ReportRepository {
        val canceledIds = mutableSetOf<Long>()
        var exports = 0
        val file = CsvFile("yamu-payments-2026-11.csv", byteArrayOf(1, 2, 3))
        var exportResult: ApiResult<CsvFile> = ApiResult.Success(file)
        /** 달마다 응답까지 걸리는 시간(ms) */
        val delays = mutableMapOf<YearMonth, Long>()
        /** 받은 (쪽, 달) 순서 */
        val calls = mutableListOf<Pair<Int, YearMonth>>()

        /** 11월은 1~40번(앞 테스트 그대로), 다른 달은 달 번호 × 1000 + n — 어느 달 줄인지 날짜로도 알 수 있게 */
        private fun item(id: Long, month: YearMonth) =
            PaymentItem(id, month.atDay(8), "12:00", "가게 $id", null, 1_000, null, "혜택", excluded = false, canceled = id in canceledIds)

        override suspend fun payments(page: Int, month: YearMonth): ApiResult<PaymentsPage> {
            calls += page to month
            val wait = delays[month] ?: 100
            delay(wait)
            val base = if (month == NOV) 0L else month.monthValue * 1_000L
            val ids = (1L..40L).toList().drop((page - 1) * 20).take(20).map { base + it }
            return ApiResult.Success(PaymentsPage(40, 40_000, ids.map { item(it, month) }, page, hasNext = page < 2))
        }

        val exportMonths = mutableListOf<YearMonth>()

        override suspend fun exportCsv(month: YearMonth): ApiResult<CsvFile> {
            exports++
            exportMonths += month
            delay(300)
            return exportResult
        }

        override suspend fun monthly(month: YearMonth): ApiResult<MonthlySpend> = error("안 씀")
        override suspend fun benefits(month: YearMonth): ApiResult<BenefitReport> = error("안 씀")
    }

    private companion object {
        val NOV: YearMonth = YearMonth.of(2026, 11)
        val OCT: YearMonth = YearMonth.of(2026, 10)
        val SEP: YearMonth = YearMonth.of(2026, 9)
    }
}
