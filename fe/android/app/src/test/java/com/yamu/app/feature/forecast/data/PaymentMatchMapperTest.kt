package com.yamu.app.feature.forecast.data

import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.YamuJson
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** 예정 지출 · 실제 결제 연결 — BE PaymentMatchResponse(-319) 모양 그대로 읽고, 앱은 문구만 붙인다 */
class PaymentMatchMapperTest {
    /** 후보 응답 — 지금 연결은 취소된 결제(REVIEW_REQUIRED), 승인 시각은 UTC, 모르는 근거 · 경고 코드가 섞임 */
    private val candidatesJson = """
        {"cashflowItemId":7010,"occurrenceDate":"2026-11-05","scheduledDate":"2026-11-05","scheduledAmount":55000,
         "match":{"matchId":801,"paymentId":5106,"merchantName":"카카오T","amount":14000,"approvedAmount":14000,
                  "paymentStatus":"CANCELED","matchStatus":"REVIEW_REQUIRED","matchedAt":"2026-11-04T04:00:00Z"},
         "candidates":[
           {"paymentId":5101,"merchantName":"애플짐 역삼점","amount":55000,"approvedAmount":55000,"approvedAt":"2026-11-05T03:30:00Z",
            "userCardId":11,"cardType":"CREDIT","matchReasons":["AMOUNT_MATCH","CARD_MATCH","DATE_NEAR","NEW_REASON"],"warnings":[]},
           {"paymentId":5102,"merchantName":" ","amount":4800,"approvedAmount":4500,"approvedAt":"2026-11-04T16:10:00Z",
            "userCardId":16,"cardType":"CHECK","matchReasons":[],"warnings":["AMOUNT_MISMATCH","CARD_MISMATCH","SOMETHING_ELSE"]},
           {"paymentId":5103,"amount":6500,"approvedAt":"bad","forecastTreatment":"ACTUAL_PAYMENT_ONLY"}
         ]}
    """.trimIndent()

    @Test fun `후보 — 근거 · 경고는 아는 코드만 문장으로, 이름이 비면 '결제'`() {
        val c = YamuJson.decodeFromString<MatchCandidatesDto>(candidatesJson).toCandidates()

        assertEquals(LocalDate.of(2026, 11, 5), c.scheduledDate)
        assertEquals(55_000L, c.scheduledAmount)
        assertEquals(3, c.candidates.size)

        val first = c.candidates[0]
        assertEquals(listOf("금액 같음", "같은 카드", "예정일 근처"), first.reasons)
        assertEquals(emptyList<String>(), first.warnings)
        assertEquals(true, first.isCredit)

        val second = c.candidates[1]
        assertEquals("결제", second.merchantName)
        assertEquals(listOf("금액이 달라요", "다른 카드로 결제했어요"), second.warnings)
        assertEquals(false, second.isCredit)
        assertEquals(4_500L, second.paidAmount)   // 실제로 나간 돈은 승인 금액

        val third = c.candidates[2]
        assertNull(third.approvedDate)
        assertNull(third.isCredit)
        assertEquals(6_500L, third.paidAmount)    // 승인 금액이 없으면 결제 금액
    }

    @Test fun `승인 시각은 UTC(끝에 Z)라 한국 날짜로 바꾼다 — 오후 4시 10분(UTC)은 다음 날`() {
        val c = YamuJson.decodeFromString<MatchCandidatesDto>(candidatesJson).toCandidates()

        assertEquals(LocalDate.of(2026, 11, 5), c.candidates[0].approvedDate)
        assertEquals(LocalDate.of(2026, 11, 5), c.candidates[1].approvedDate)
        assertEquals(LocalDate.of(2026, 11, 8), utcToKstDate("2026-11-07T15:00:00Z"))
        assertEquals(LocalDate.of(2026, 11, 7), utcToKstDate("2026-11-07T14:59:59Z"))
        assertEquals(LocalDate.of(2026, 11, 8), utcToKstDate("2026-11-07T15:00:00.123Z"))
        assertNull(utcToKstDate(null))
    }

    @Test fun `Z가 없는 값도 UTC로 읽는다`() {
        assertEquals(LocalDate.of(2026, 11, 8), utcToKstDate("2026-11-07T15:00:00"))
        assertEquals(LocalDate.of(2026, 11, 7), utcToKstDate("2026-11-07T14:59:59"))
        assertEquals(LocalDate.of(2026, 11, 8), utcToKstDate("2026-11-07T18:00:00+03:00"))   // 오프셋이 오면 그 오프셋대로
        assertNull(utcToKstDate("2026-11-07"))
    }

    @Test fun `지금 연결 — 결제가 취소되면 다시 확인할 상태, 모르는 상태 값은 UNKNOWN`() {
        val c = YamuJson.decodeFromString<MatchCandidatesDto>(candidatesJson).toCandidates()

        assertEquals(MatchState.REVIEW_REQUIRED, c.match?.state)
        assertEquals(801L, c.match?.matchId)
        assertEquals(MatchState.UNKNOWN, MatchViewDto(1, 2, matchStatus = "NEW_STATE").toMatch().state)
        assertEquals("결제", MatchViewDto(1, 2).toMatch().merchantName)
    }

    @Test fun `연결 상태 — 연결이 없으면 match가 null`() {
        val none = YamuJson.decodeFromString<MatchStatusDto>("""{"cashflowItemId":7010,"occurrenceDate":"2026-11-05","match":null}""")
        assertNull(none.match)

        val active = YamuJson.decodeFromString<MatchStatusDto>(
            """{"cashflowItemId":7011,"occurrenceDate":"2026-11-02","match":{"matchId":9,"paymentId":5105,"merchantName":"연세내과의원",
               "amount":18000,"approvedAmount":18000,"paymentStatus":"APPROVED","matchStatus":"ACTIVE","matchedAt":"2026-11-02T04:00:00"}}""",
        )
        assertEquals(MatchState.ACTIVE, active.match?.toMatch()?.state)
    }

    @Test fun `확정 응답 — 경고는 문장으로(연결은 됐고 알리기만)`() {
        val dto = YamuJson.decodeFromString<MatchConfirmedDto>(
            """{"matchId":803,"cashflowItemId":7012,"occurrenceDate":"2026-11-10","paymentId":5104,"matchStatus":"ACTIVE",
               "matchedAt":"2026-11-08T03:00:00","warnings":["CARD_MISMATCH","DATE_FAR","MERCHANT_CATEGORY_MISMATCH","CARD_MISMATCH"],
               "forecastTreatment":"ACTUAL_PAYMENT_ONLY"}""",
        )
        val confirmed = dto.toConfirmed()

        assertEquals(803L, confirmed.matchId)
        assertEquals(listOf("다른 카드로 결제했어요", "예정일과 7일 넘게 떨어져 있어요", "업종이 달라요"), confirmed.warnings)
        assertEquals(MatchState.ACTIVE, confirmed.state)
        assertEquals(MatchState.ACTIVE, YamuJson.decodeFromString<MatchConfirmedDto>("""{"matchId":804}""").toConfirmed().state)
    }

    @Test fun `확정 직후 연결 — 응답에 없는 가맹점 · 금액은 고른 후보에서, 후보를 못 찾으면 '결제'`() {
        val picked = MatchCandidate(5101, "애플짐 역삼점", 55_000, 52_000, LocalDate.of(2026, 11, 5), true, emptyList(), emptyList())
        val confirmed = MatchConfirmed(803, emptyList(), MatchState.ACTIVE)

        assertEquals(PaymentMatch(803, 5101, "애플짐 역삼점", 55_000, 52_000, MatchState.ACTIVE), confirmed.toMatch(5101, picked))
        assertEquals(PaymentMatch(803, 5104, "결제", null, null, MatchState.ACTIVE), confirmed.toMatch(5104, null))
    }

    @Test fun `확정 요청 본문 — 회차 날짜는 원래 회차(yyyy-MM-dd)`() {
        val body = YamuJson.encodeToString(MatchConfirmRequest.serializer(), MatchConfirmRequest(7012, LocalDate.of(2026, 11, 10).toString(), 5104))
        assertEquals("""{"cashflowItemId":7012,"occurrenceDate":"2026-11-10","paymentId":5104}""", body)
    }

    @Test fun `연결할 수 있는 회차는 카드를 지정한 예정 지출만`() {
        fun entry(isIncome: Boolean, userCardId: Long?) = CashflowEntry(
            1, isIncome, "x", 1_000, LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 5), CashflowRepeat.NONE, null, null,
            accountId = if (userCardId == null) 1 else null, userCardId = userCardId,
        )
        assertTrue(entry(isIncome = false, userCardId = 11).isMatchable)
        assertFalse(entry(isIncome = false, userCardId = null).isMatchable)
        assertFalse(entry(isIncome = true, userCardId = 11).isMatchable)
    }

    @Test fun `실패 문구 — 409는 다른 상대와 이미 연결, 400은 서버가 준 칸 이유를 먼저`() {
        assertEquals("이 회차나 결제가 이미 다른 것과 연결돼 있어요 · 목록을 새로 불러왔어요", ApiError.from(409, "CASHFLOW_MATCH_CONFLICT").toMatchMessage())
        assertEquals("예정 지출이나 결제를 찾을 수 없어요 · 목록을 새로 불러왔어요", ApiError.NotFound("RESOURCE_NOT_FOUND").toMatchMessage())
        val fieldError = ApiError.InvalidRequest(
            "INVALID_REQUEST",
            buildJsonObject { putJsonArray("fieldErrors") { addJsonObject { put("field", "paymentId"); put("reason", "승인된 결제만 연결할 수 있습니다.") } } },
        )
        assertEquals("승인된 결제만 연결할 수 있습니다.", fieldError.toMatchMessage())
        assertEquals("연결할 수 없는 결제예요 · 취소됐거나 카드를 지정하지 않은 예정 지출일 수 있어요", ApiError.InvalidRequest("INVALID_REQUEST").toMatchMessage())
        assertEquals("인터넷 연결을 확인해 주세요", ApiError.Network.toMatchMessage())
    }
}
