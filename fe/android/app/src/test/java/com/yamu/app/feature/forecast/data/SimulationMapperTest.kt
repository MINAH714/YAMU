package com.yamu.app.feature.forecast.data

import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.YamuJson
import com.yamu.app.feature.link.data.CardDto
import com.yamu.app.feature.link.data.IssuerDto
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

/** 가상 구매 — BE POST /api/forecasts/simulations 요청 · 응답 · 오류(2026-11-06 develop) */
class SimulationMapperTest {
    @Test fun `업종의 모의 구매 지원 여부를 보존하고 누락된 응답은 허용하지 않는다`() {
        for (code in listOf("ELECTRIC_UTILITY", "UNIVERSITY", "TAXI")) {
            val dto = YamuJson.decodeFromString<MerchantCategoryDto>(
                """{"merchantCategoryCode":"$code","merchantCategoryName":"제외 업종","simulationSupported":false}""",
            )
            assertFalse(dto.toCategory().simulationSupported)
        }
        val allowed = YamuJson.decodeFromString<MerchantCategoryDto>(
            """{"merchantCategoryCode":"COFFEE_SHOP","merchantCategoryName":"카페","simulationSupported":true}""",
        )
        assertTrue(allowed.toCategory().simulationSupported)
        val old = YamuJson.decodeFromString<MerchantCategoryDto>(
            """{"merchantCategoryCode":"TAXI","merchantCategoryName":"택시"}""",
        )
        assertFalse(old.toCategory().simulationSupported)
    }
    private val nov8 = LocalDate.of(2026, 11, 8)
    private val query = SimulationQuery(
        amount = 890_000, productName = "태블릿", categoryCode = "HYPERMARKET", categoryName = "대형마트",
        merchantId = null, merchantName = null, channel = PaymentChannel.OFFLINE, userCardId = null, installmentMonths = null, purchaseDate = nov8,
    )

    private fun body(q: SimulationQuery): JsonObject =
        YamuJson.encodeToJsonElement(SimulationRequest.serializer(), q.toRequest(listOf(3, 4))).jsonObject

    @Test fun `요청 — paymentChannel은 늘, 일시불이면 plan · installmentMonths · userCardId를 아예 빼고 보낸다`() {
        val json = body(query)
        assertEquals(JsonPrimitive("OFFLINE"), json["paymentChannel"])
        assertFalse("plan" in json)                     // null을 보내면 BE가 '보냈다'로 보고 400
        assertFalse("installmentMonths" in json)
        assertFalse("userCardId" in json)               // 빼면 혜택이 가장 큰 카드
        assertFalse("merchantId" in json)
        assertEquals(JsonPrimitive("HYPERMARKET"), json["merchantCategoryCode"])
        assertEquals(JsonPrimitive("2026-11-08"), json["purchaseDate"])
        assertEquals("[3,4]", json["accountIds"].toString())
    }

    @Test fun `요청 — 기간은 늘 오늘부터 30일, 계좌별 전후도 받는다(-589)`() {
        val json = body(query)
        assertEquals(JsonPrimitive("DAYS"), json["until"])     // N일은 until=DAYS와 days를 같이(BE -318)
        assertEquals(JsonPrimitive(30), json["days"])
        assertEquals(JsonPrimitive(true), json["includeAccountDetails"])
    }

    @Test fun `요청 — 할부 · 카드 · 온라인`() {
        val json = body(query.copy(installmentMonths = 3, userCardId = 11, channel = PaymentChannel.ONLINE, merchantId = 201))
        assertEquals(JsonPrimitive("INSTALLMENT"), json["plan"])
        assertEquals(JsonPrimitive(3), json["installmentMonths"])
        assertEquals(JsonPrimitive(11), json["userCardId"])
        assertEquals(JsonPrimitive("ONLINE"), json["paymentChannel"])
    }

    /** 사용자가 생활카드를 골랐는데 1순위는 알뜰카드 · 비교 못 한 카드 · 소수 혜택 · 기간 밖 회차 · 모르는 빠진 정보 코드 */
    private val response = """
        {"input":{"amount":890000,"paymentChannel":"OFFLINE"},
         "card":{"userCardId":11,"nickname":"생활카드","cardType":"CREDIT","recommended":false},
         "benefit":{"netBenefit":8900.5,"benefits":[{"benefitId":1,"name":"생활 업종 1% 청구할인","type":"DISCOUNT","realization":"BILLING","amount":8900.5},
                                                  {"benefitId":2,"name":"배분 미정","type":"POINT","amount":null}],"chargedAmount":890000},
         "schedule":[{"date":"2026-11-20","amount":-296668,"label":"1/3회차 (11월 청구)"},{"date":"2026-12-21","amount":-296666,"label":"2/3회차 (12월 청구)"}],
         "comparison":{"before":{"minBalance":360000,"minBalanceDate":"2026-12-07","endBalance":360000,"status":"SAFE"},
                       "after":{"minBalance":63332,"minBalanceDate":"2026-12-07","endBalance":63332,"status":"WARNING"},
                       "withinPeriodImpact":-296668,"beyondPeriodImpact":-296666},
         "series":[{"date":"2026-11-08","before":520000,"after":520000},{"date":"2026-12-07","before":360000,"after":63332}],
         "performanceImpact":null,
         "alternatives":[{"userCardId":12,"nickname":"알뜰카드","cardType":"CREDIT","plan":"INSTALLMENT","installmentMonths":3,"netBenefit":12000,"note":null,"recommended":true},
                         {"userCardId":16,"nickname":"알뜰체크","cardType":"CHECK","plan":"LUMP_SUM","netBenefit":null,"note":"체크카드는 할부를 쓸 수 없어요","recommended":false}],
         "assumptions":[{"id":"YAMU_PURCHASE_TIME_NOON","description":"구매일 낮 12시에 결제한다고 보고 계산했어요."}],
         "missingInformation":[{"code":"BRAND_MISSING","field":"merchantId"},{"code":"SOMETHING_NEW","field":null}],
         "creditLimit":{"status":"SKIPPED","availableLimit":null},
         "outsideSelectionImpact":null,"accountComparisons":null,
         "message":"12월 7일 잔액이 63,332원까지 내려가요. 예보 기간 밖에서 296,666원이 더 빠져요.","isEstimate":true}
    """.trimIndent()

    private fun parse(q: SimulationQuery = query.copy(userCardId = 11, installmentMonths = 3)) =
        YamuJson.decodeFromString(SimulationResponseDto.serializer(), response).toSimulation(q)

    @Test fun `응답 — 카드 · 소수 혜택 · 금액 없는 혜택 줄 · 청구 금액`() {
        val s = parse()
        assertEquals(SimCard(11, "생활카드", isCredit = true, recommendedTop = false), s.card)
        assertEquals(BigDecimal("8900.5"), s.benefit!!.net)
        assertNull(s.benefit!!.lines[1].amount)
        assertEquals(890_000L, s.benefit!!.charged)
        assertEquals("12월 7일 잔액이 63,332원까지 내려가요. 예보 기간 밖에서 296,666원이 더 빠져요.", s.message)
    }

    @Test fun `응답 — 전후 · 기간 밖 회차 · 신용한도 미확인`() {
        val s = parse()
        assertEquals(360_000L, s.before!!.minBalance)
        assertEquals(RiskLevel.WARNING, s.after!!.status)
        assertEquals(listOf(false, true), s.schedule.map { it.beyondPeriod })   // 그래프 마지막 날(12/7) 뒤면 기간 밖
        assertTrue(s.creditLimitSkipped)
        assertEquals(2, s.days.size)
    }

    @Test fun `부족 시작일 — BE riskStartDate를 그대로 읽고, 가장 낮은 날과 따로 둔다(-347 !198)`() {
        val withField = response
            .replace(""""status":"SAFE"}""", """"status":"SAFE","riskStartDate":null}""")
            .replace(""""status":"WARNING"}""", """"status":"WARNING","riskStartDate":"2026-11-20"}""")
            .replace("""296,666원이 더 빠져요.""", """296,666원이 더 빠져요. 체크카드는 구매일 잔액보다 큰 금액은 결제되지 않을 수 있어요.""")
        val s = YamuJson.decodeFromString(SimulationResponseDto.serializer(), withField).toSimulation(query)
        assertNull(s.before!!.riskStartDate)   // SAFE면 null
        assertEquals(LocalDate.of(2026, 11, 20), s.after!!.riskStartDate)
        assertEquals(LocalDate.of(2026, 12, 7), s.after!!.minBalanceDate)
        // 체크카드 잔액 초과 안내는 BE 문장 끝에 붙어 오고(!198, 16b1791b), 결과 화면은 message를 그대로 보인다
        assertTrue(s.message!!.endsWith("체크카드는 구매일 잔액보다 큰 금액은 결제되지 않을 수 있어요."))
    }

    @Test fun `부족 시작일 — 칸이 없는 옛 응답이면 날짜를 계산해 채우지 않는다(!206 리뷰)`() {
        // 날짜별 잔액은 통합 선뿐이라, 계좌 하나가 먼저 모자라면 늦은 날이 나오거나 빠진다 — 지어내지 않고 비운다
        val s = parse()
        assertNull(s.after!!.riskStartDate)
        assertNull(s.before!!.riskStartDate)
    }

    // ── 카드 출금 계좌 부족(-589) — 계좌 3(카드 11) · 계좌 4(카드 16) ──

    private val kb = ForecastAccountOption(3, "KB 입출금", "KB국민은행", "1234", 300_000, isPrimary = true, cardIds = setOf(11))
    private val shinhan = ForecastAccountOption(4, "신한 입출금", "신한은행", "5678", 200_000, isPrimary = false, cardIds = setOf(16))

    /** 계좌 3의 사기 전 · 후 최저 잔액과 날짜 */
    private fun withAccounts(before: Long, after: Long, date: String = "2026-11-20") = response.replace(
        """"accountComparisons":null""",
        """"accountComparisons":[
            {"accountId":3,"before":{"minBalance":$before,"minBalanceDate":"2026-11-10","endBalance":$before,"status":"SAFE"},
                           "after":{"minBalance":$after,"minBalanceDate":"$date","endBalance":$after,"status":"DANGER","riskStartDate":"2026-11-12"}},
            {"accountId":4,"before":{"minBalance":-5000,"minBalanceDate":"2026-11-15","endBalance":0,"status":"DANGER"},
                           "after":{"minBalance":-5000,"minBalanceDate":"2026-11-15","endBalance":0,"status":"DANGER"}}]""",
    )

    private fun parseWith(json: String, accounts: List<ForecastAccountOption> = listOf(kb, shinhan)) =
        YamuJson.decodeFromString(SimulationResponseDto.serializer(), json).toSimulation(query.copy(userCardId = 11)).copy(accounts = accounts)

    @Test fun `응답 — 계좌별 전후를 읽고, 옛 응답(null)이면 비운다`() {
        val s = parseWith(withAccounts(before = 50_000, after = -40_000))
        assertEquals(listOf(3L, 4L), s.accountComparisons.map { it.accountId })
        assertEquals(-40_000L, s.accountComparisons[0].after!!.minBalance)
        assertEquals(RiskLevel.DANGER, s.accountComparisons[0].after!!.status)
        assertEquals(LocalDate.of(2026, 11, 12), s.accountComparisons[0].after!!.riskStartDate)
        assertTrue(parse().accountComparisons.isEmpty())
    }

    @Test fun `카드 출금 계좌가 0원 아래면 어느 계좌에 얼마 — 모자란 몫 전체와 가장 모자랄 때`() {
        val s = parseWith(withAccounts(before = -10_000, after = -40_000))
        val shortfall = s.cardAccountShortfall!!
        assertEquals(kb, shortfall.account)
        assertEquals(40_000L, shortfall.amount)   // 구매로 늘어난 30,000원이 아니라 0원 아래 전체
        assertEquals(LocalDate.of(2026, 11, 20), shortfall.date)
        assertEquals("이 카드로 결제하면 KB국민은행 ··1234 계좌에 40,000원이 부족해요", shortfall.text)
        assertEquals("KB국민은행 ··1234", s.cardAccount!!.shortName())
    }

    @Test fun `카드 출금 계좌 부족 — 0원이거나 위면, 구매가 최저점을 바꾸지 않았으면 안내하지 않는다`() {
        assertNull(parseWith(withAccounts(before = 50_000, after = 0)).cardAccountShortfall)
        assertNull(parseWith(withAccounts(before = 50_000, after = 10_000)).cardAccountShortfall)
        // 사기 전부터 모자랐고 구매(기간 밖 출금 등)가 그 최저점을 바꾸지 않았다 — 이 카드 탓이 아니다
        assertNull(parseWith(withAccounts(before = -40_000, after = -40_000)).cardAccountShortfall)
    }

    @Test fun `카드 출금 계좌 부족 — 다른 계좌만 모자라거나, 계좌를 모르거나, 계좌별 전후가 없으면 안내하지 않는다`() {
        // 계좌 4(카드 16)가 −5,000원이지만 고른 카드는 11(계좌 3)이다
        assertNull(parseWith(withAccounts(before = 50_000, after = 10_000)).cardAccountShortfall)
        // 카드 11의 출금 계좌가 연결 계좌 목록에 없다(연결을 끊은 계좌)
        assertNull(parseWith(withAccounts(before = 50_000, after = -40_000), accounts = listOf(shinhan)).cardAccountShortfall)
        // 옛 응답
        assertNull(parse().copy(accounts = listOf(kb, shinhan)).cardAccountShortfall)
    }

    @Test fun `카드 출금 계좌 — 계산한 계좌 중 카드가 묶인 계좌, 목록에 없으면 null`() {
        val s = parse()   // 카드 11
        assertEquals(4L, s.copy(accounts = listOf(kb.copy(cardIds = setOf(12)), shinhan.copy(cardIds = setOf(21, 11)))).cardAccount!!.accountId)
        assertNull(s.copy(accounts = listOf(shinhan)).cardAccount)
        assertNull(s.cardAccount)
    }

    @Test fun `계좌 이름표 — 하나면 그 계좌, 여럿이면 개수와 합`() {
        assertEquals("계좌 2개 합", parse().copy(accounts = listOf(kb, shinhan)).accountsLabel)
        assertEquals("KB국민은행 ··1234", parse().copy(accounts = listOf(kb)).accountsLabel)
        assertNull(parse().accountsLabel)
    }

    @Test fun `업종을 고르면 맞추는 결제 방식 — 온라인 업종 넷만 온라인(점검 B-14)`() {
        listOf("ONLINE_MARKETPLACE", "DELIVERY_APP", "STREAMING_SERVICE", "MEMBERSHIP_SUBSCRIPTION").forEach {
            assertEquals(it, PaymentChannel.ONLINE, PaymentChannel.forCategory(it))
        }
        listOf("HYPERMARKET", "CONVENIENCE_STORE", "COFFEE_SHOP", "TELECOMMUNICATION", "OTHER", "SOMETHING_NEW").forEach {
            assertEquals(it, PaymentChannel.OFFLINE, PaymentChannel.forCategory(it))
        }
    }

    @Test fun `응답 — 다른 카드는 1순위 표시 · 비교 못 한 카드는 이유`() {
        val s = parse()
        assertTrue(s.alternatives.single { it.userCardId == 12L }.recommended)
        val check = s.alternatives.single { it.userCardId == 16L }
        assertNull(check.net)
        assertEquals("체크카드는 할부를 쓸 수 없어요", check.note)
        assertEquals("신용 · 할부 3개월", s.alternatives.first().detail)
    }

    @Test fun `응답 — 빠진 정보는 아는 코드만 문장, 모르는 코드는 일반 문장`() {
        assertEquals(
            listOf("브랜드를 고르면 브랜드 혜택까지 계산할 수 있어요.", "혜택 조건 중 확인하지 못한 것이 있어 일부 혜택은 빼고 계산했어요."),
            parse().missing,
        )
    }

    @Test fun `혜택을 계산하지 못한 응답(benefit null) · 추천 1순위로 계산한 응답`() {
        val dto = YamuJson.decodeFromString(SimulationResponseDto.serializer(), response)
        val s = dto.copy(benefit = null, card = dto.card!!.copy(recommended = true), alternatives = null).toSimulation(query)
        assertNull(s.benefit)
        assertTrue(s.card.recommendedTop)
        assertTrue(s.alternatives.isEmpty())
        // '혜택은 계산하지 못했어요' 아래 이유로 읽혀야 한다 — '일부 혜택은 빼고 계산했어요'가 나오면 서로 어긋난다(로컬 BE 확인에서 찾음)
        assertEquals(listOf("브랜드를 고르면 브랜드 혜택까지 계산할 수 있어요.", "확인하지 못한 혜택 조건이 있어요."), s.missing)
        assertTrue(s.missing.none { "빼고 계산했어요" in it })
    }

    @Test fun `추천 엔진 읽기 실패 · 고른 카드 미평가는 따로 문장(!176 리뷰)`() {
        assertEquals("혜택 정보를 읽지 못했어요. 잠시 뒤 다시 시도해 주세요.", missingText("RECOMMENDATION_READ_FAILED", benefitComputed = false))
        assertEquals("이 카드는 이번 계산에서 혜택을 비교하지 못했어요.", missingText("CARD_NOT_EVALUATED", benefitComputed = false))
    }

    @Test fun `계획 저장 — 일시불만, 전액 즉시할인이면 저장할 것이 없다`() {
        val lump = parse(query.copy(userCardId = 11))
        assertTrue(lump.canSavePlan)
        val body = lump.toPlanRequest()
        assertEquals("EXPENSE", body.type)
        assertEquals("태블릿", body.title)
        assertEquals(890_000L, body.amount)
        assertEquals(11L, body.userCardId)
        assertNull(body.accountId)
        assertEquals("2026-11-08", body.date)
        assertFalse(parse(query.copy(installmentMonths = 3)).canSavePlan)
        assertFalse(lump.copy(benefit = lump.benefit!!.copy(charged = 0)).canSavePlan)
        assertEquals("구매 계획", lump.copy(query = lump.query.copy(productName = " ")).toPlanRequest().title)
    }

    private fun failure(status: Int, code: String, reason: String? = null, field: String? = null) = ApiError.from(
        status,
        code,
        buildJsonObject {
            reason?.let { put("reason", it) }
            field?.let { f -> putJsonArray("fieldErrors") { addJsonObject { put("field", f); put("reason", "x") } } }
        },
    )

    @Test fun `422 이유마다 문구와 다음 행동`() {
        val cases = mapOf(
            "CATEGORY_REQUIRED" to SimAction.PICK_CATEGORY,
            "NO_ELIGIBLE_CARD" to SimAction.PICK_CARD,
            "BENEFIT_DATA_INSUFFICIENT" to SimAction.PICK_CARD,
            "PAYMENT_AVAILABILITY_UNKNOWN" to SimAction.RETRY,
            "RECOMMENDATION_UNAVAILABLE" to SimAction.PICK_CARD,
            "CARD_ACCOUNT_UNKNOWN" to SimAction.OTHER_CARD,
            "BILLING_CYCLE_UNKNOWN" to SimAction.OTHER_CARD,
            "BRAND_NEW_REASON" to SimAction.RETRY,
        )
        cases.forEach { (reason, action) -> assertEquals(reason, action, failure(422, "DATA_INSUFFICIENT", reason).toSimProblem().action) }
        assertEquals("어디서 살지 골라 주세요", failure(422, "DATA_INSUFFICIENT", "CATEGORY_REQUIRED").toSimProblem().title)
        assertEquals("지금 이 구매에 쓸 수 있는 카드가 없어요", failure(422, "DATA_INSUFFICIENT", "NO_ELIGIBLE_CARD").toSimProblem().title)
    }

    @Test fun `400 — 결제할 수 없는 카드 · 할부 조건 · 지난 구매일 · 업종 없음`() {
        val unpaid = failure(400, "CARD_NOT_ELIGIBLE", "UNPAID_BILL").toSimProblem()
        assertEquals("미납 청구서가 있어 결제할 수 없는 카드예요", unpaid.title)
        assertTrue(unpaid.toRecommended)
        assertEquals("꺼 둔 카드예요", failure(400, "CARD_NOT_ELIGIBLE", "CARD_INACTIVE").toSimProblem().title)
        assertEquals(SimAction.LUMP_SUM, failure(400, "INVALID_INSTALLMENT").toSimProblem().action)
        assertEquals(SimAction.PICK_DATE, failure(400, "INVALID_REQUEST", field = "purchaseDate").toSimProblem().action)
        assertEquals(SimAction.PICK_CATEGORY, failure(400, "MERCHANT_OR_CATEGORY_REQUIRED").toSimProblem().action)
        assertEquals(SimAction.RETRY, ApiError.BankUnavailable("FIN_API_UNAVAILABLE").toSimProblem().action)
        assertEquals(SimAction.RETRY, ApiError.Network.toSimProblem().action)
    }

    @Test fun `카드 고르기 줄 — 등록 중 · 해제는 빼고, 꺼 둔 · 정지 카드는 이유를 단다`() {
        fun card(id: Long, status: String?, issuer: String? = "NORMAL", type: String = "CREDIT") =
            CardDto(id, nickname = null, issuer = IssuerDto(name = "신한"), productName = "카드$id", cardType = type, maskedNumber = "****", status = status, issuerStatus = issuer)
        val options = listOf(card(1, "ACTIVE"), card(2, "INACTIVE"), card(3, "PENDING"), card(4, "ACTIVE", "SUSPENDED"), card(5, "UNREGISTERED"), card(6, null, null, "CHECK"))
            .mapNotNull { it.toSimCardOption() }
        assertEquals(listOf(1L, 2L, 4L, 6L), options.map { it.userCardId })
        assertEquals(listOf(null, "꺼 둔 카드", "카드사에서 정지한 카드", null), options.map { it.unusableReason })
        assertFalse(options.last().isCredit)
        assertEquals("카드1", options.first().name)                           // 별칭이 없으면 상품명
        assertEquals("신한", options.first().brand?.name)                     // 그림이 없을 때 그리는 카드사 색 카드
    }

    @Test fun `받침 따라 이 · 가`() {
        assertEquals("가", "알뜰카드".subjectParticle())
        assertEquals("이", "생활 통장".subjectParticle())
    }
}
