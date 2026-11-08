package com.yamu.app.feature.report

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
import com.yamu.app.feature.report.data.ReportRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
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
import java.time.LocalDate

/** S-05 — 취소 · 메모 뒤 돌아오면 첫 쪽부터, CSV는 저장 창에서 고른 뒤에 받는다 */
class PaymentsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repo = StubReports()
    private val edits = PaymentEdits()
    private val docs = StubDocuments()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `그대로 돌아오면 펼친 쪽을 두고 첫 쪽만 합친다`() = runTest(dispatcher) {
        val vm = PaymentsViewModel(repo, edits, docs)
        vm.refresh(); advanceUntilIdle()
        vm.loadMore(); advanceUntilIdle()
        assertEquals(40, vm.state.value.items.size)
        vm.refresh(); advanceUntilIdle()
        assertEquals(40, vm.state.value.items.size)
        assertEquals(2, vm.state.value.page)
    }

    @Test fun `그사이 취소 · 메모가 있었으면 첫 쪽부터 다시 — 뒤쪽 줄이 옛 상태로 남지 않게`() = runTest(dispatcher) {
        val vm = PaymentsViewModel(repo, edits, docs)
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

    @Test fun `CSV — 저장 창을 먼저 띄우고 고른 곳에 받아 쓴다, 연타해도 한 번`() = runTest(dispatcher) {
        val vm = PaymentsViewModel(repo, edits, docs)
        vm.requestCsv()
        assertEquals(defaultCsvName(), vm.state.value.saveAs)   // 화면과 같은 이번 달 이름
        assertEquals(0, repo.exports)                           // 고르기 전에는 받지 않는다
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

    @Test fun `저장 창을 닫으면 받지 않는다 — 분당 3번 한도를 쓰지 않게`() = runTest(dispatcher) {
        val vm = PaymentsViewModel(repo, edits, docs)
        vm.requestCsv(); vm.saveAsShown()
        vm.saveCsvTo(null)
        advanceUntilIdle()
        assertEquals(0, repo.exports)
        assertNull(vm.state.value.message)
    }

    @Test fun `분당 3번 넘음 — 창이 만든 빈 파일을 지우고 남은 초를 알린다`() = runTest(dispatcher) {
        repo.exportResult = ApiResult.Failure(ApiError.from(429, "RATE_LIMITED", buildJsonObject { put("retryAfterSeconds", 42) }))
        val vm = PaymentsViewModel(repo, edits, docs)
        vm.saveCsvTo("content://doc/2"); advanceUntilIdle()
        assertEquals(listOf("content://doc/2"), docs.discarded)
        assertFalse(vm.state.value.exporting)
        assertEquals("내려받기는 1분에 3번까지예요. 42초 뒤 다시 눌러 주세요", vm.state.value.message)
    }

    @Test fun `파일을 못 쓰면 빈 파일을 지우고 다시 저장하라고 알린다`() = runTest(dispatcher) {
        docs.writeOk = false
        val vm = PaymentsViewModel(repo, edits, docs)
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

        override suspend fun discard(uri: String) {
            discarded += uri
        }
    }

    private class StubReports : ReportRepository {
        val canceledIds = mutableSetOf<Long>()
        var exports = 0
        val file = CsvFile("yamu-payments-2026-11.csv", byteArrayOf(1, 2, 3))
        var exportResult: ApiResult<CsvFile> = ApiResult.Success(file)

        private fun item(id: Long) = PaymentItem(id, LocalDate.of(2026, 11, 8), "12:00", "가게 $id", null, 1_000, null, "혜택", excluded = false, canceled = id in canceledIds)

        override suspend fun payments(page: Int): ApiResult<PaymentsPage> {
            delay(100)
            val ids = (1L..40L).toList().drop((page - 1) * 20).take(20)
            return ApiResult.Success(PaymentsPage(40, 40_000, ids.map(::item), page, hasNext = page < 2))
        }

        override suspend fun exportCsv(): ApiResult<CsvFile> {
            exports++
            delay(300)
            return exportResult
        }

        override suspend fun monthly(): ApiResult<MonthlySpend> = error("안 씀")
        override suspend fun benefits(): ApiResult<BenefitReport> = error("안 씀")
    }
}
