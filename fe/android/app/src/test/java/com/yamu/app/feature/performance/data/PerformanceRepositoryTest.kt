package com.yamu.app.feature.performance.data

import com.yamu.app.core.network.ApiEnvelope
import com.yamu.app.core.network.ApiResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.io.IOException

/** R-01 목록이 #18(유불리 분석)로 판정을 채우는 방식 — BE 375 전에는 #18이 없어 API_NOT_FOUND */
class PerformanceRepositoryTest {

    private class FakeApi(
        private val cards: List<CardPerformanceItemDto>,
        var analysisReply: (Long) -> Response<ApiEnvelope<PerformanceAnalysisDto>>,
    ) : PerformanceApi {
        val analysisCalls = mutableListOf<Long>()

        override suspend fun overview(yearMonth: String?): Response<ApiEnvelope<CardPerformancesDto>> =
            Response.success(ApiEnvelope(success = true, data = CardPerformancesDto(yearMonth = "2026-11", daysLeft = 20, cards = cards)))

        override suspend fun analysis(userCardId: Long, scenarioMoveItemIds: String?): Response<ApiEnvelope<PerformanceAnalysisDto>> {
            synchronized(analysisCalls) { analysisCalls += userCardId }
            return analysisReply(userCardId)
        }

        override suspend fun detail(userCardId: Long): Response<ApiEnvelope<PerformanceDetailDto>> = error("부르지 않음")
        override suspend fun decide(userCardId: Long, body: JsonObject): Response<ApiEnvelope<JsonElement>> = error("부르지 않음")
        override suspend fun moveCashflow(cashflowItemId: Long, body: MoveCashflowRequest): Response<ApiEnvelope<JsonElement>> = error("부르지 않음")
        override suspend fun limits(): Response<ApiEnvelope<BenefitLimitsDto>> = error("부르지 않음")
    }

    private fun failure(status: Int, code: String): Response<ApiEnvelope<PerformanceAnalysisDto>> =
        Response.error(status, """{"success":false,"error":{"code":"$code"}}""".toResponseBody("application/json".toMediaType()))

    private fun keepNatural(id: Long): Response<ApiEnvelope<PerformanceAnalysisDto>> =
        Response.success(ApiEnvelope(success = true, data = PerformanceAnalysisDto(userCardId = id, remainingAmount = 50_000, guide = "KEEP_NATURAL")))

    /** 기준 전 세 장(판정 없음 · AT_RISK) + 달성 한 장 — 지금 BE #65 모양(guide null) */
    private val cards = listOf(11L, 13L, 14L).map {
        CardPerformanceItemDto(it, nickname = "카드$it", targetAmount = 300_000, recognizedAmount = 250_000, remainingAmount = 50_000, status = "AT_RISK")
    } + CardPerformanceItemDto(15, nickname = "카드15", targetAmount = 300_000, recognizedAmount = 320_000, status = "ACHIEVED")

    @Test fun `#18이 없으면 한 장만 보고 멈추고, 다음 새로고침부터는 부르지 않는다`() = runTest {
        val api = FakeApi(cards) { failure(404, "API_NOT_FOUND") }
        val repo = PerformanceRepositoryImpl(api)

        val first = (repo.overview() as ApiResult.Success).data
        assertEquals(1, api.analysisCalls.size)
        assertEquals(listOf(11L, 13L, 14L), first.pending.map { it.userCardId })   // 판단 보류 순서 그대로(남은 금액이 같으면 받은 순)
        assertTrue(first.pending.all { it.guide == null && it.tagLabel == "미달 예상" })

        repo.overview()
        repo.overview()
        assertEquals(1, api.analysisCalls.size)
    }

    @Test fun `405도 없는 API로 본다`() = runTest {
        val api = FakeApi(cards) { failure(405, "METHOD_NOT_ALLOWED") }
        val repo = PerformanceRepositoryImpl(api)
        repo.overview()
        repo.overview()
        assertEquals(1, api.analysisCalls.size)
    }

    @Test fun `카드 없음 · 연결 끊김으로는 끄지 않는다`() = runTest {
        val api = FakeApi(cards) { failure(404, "RESOURCE_NOT_FOUND") }
        val repo = PerformanceRepositoryImpl(api)
        repo.overview()
        assertEquals(3, api.analysisCalls.size)

        api.analysisReply = { throw IOException("끊김") }
        repo.overview()
        assertEquals(6, api.analysisCalls.size)
    }

    @Test fun `#18이 있으면 기준 전 카드 판정을 채운다`() = runTest {
        val api = FakeApi(cards) { keepNatural(it) }
        val repo = PerformanceRepositoryImpl(api)

        val o = (repo.overview() as ApiResult.Success).data
        assertEquals(listOf(11L, 13L, 14L), api.analysisCalls.sorted())   // 달성 카드는 부르지 않는다
        assertTrue(o.pending.all { it.guide == PerfGuide.KEEP_NATURAL && it.tagLabel == "자연 소비 유지" })

        repo.overview()
        assertEquals(6, api.analysisCalls.size)
    }

    @Test fun `R-03에서 직접 불러 성공하면 목록에서도 다시 부른다`() = runTest {
        val api = FakeApi(cards) { failure(404, "API_NOT_FOUND") }
        val repo = PerformanceRepositoryImpl(api)
        repo.overview()
        assertEquals(1, api.analysisCalls.size)

        api.analysisReply = { keepNatural(it) }   // 앱을 켜 둔 사이 375 배포
        assertTrue(repo.analysis(11) is ApiResult.Success)
        api.analysisCalls.clear()

        repo.overview()
        assertEquals(3, api.analysisCalls.size)
    }
}
