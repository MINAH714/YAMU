package com.yamu.app.feature.link.data

import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.network.YamuHeaders
import com.yamu.app.core.network.YamuJson
import com.yamu.app.core.network.json
import com.yamu.app.core.network.retrofit
import com.yamu.app.feature.auth.data.AccountCandidatesResponse
import com.yamu.app.feature.auth.data.toCandidate
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 요청 · 응답 JSON이 BE card/dto · account/dto와 같은지 — 값은 record와 같은 이름 · 모양 */
class CardRegisterContractTest {
    private inline fun <reified T> fields(value: T) = YamuJson.parseToJsonElement(YamuJson.encodeToString(value)).jsonObject

    private val lookupJson = """
        {"verificationToken":"cvt_1","expiresAt":"2026-11-03T01:05:00Z",
         "issuer":{"code":"SHINHAN","name":"신한카드","color":"#0046FF","closingDayOffset":14},
         "product":{"cardProductId":3,"name":"신한카드 Mr.Life","cardType":"CREDIT","imageUrl":null},
         "maskedNumber":"9410-****-****-5678","holderMatched":true,"hasPriorHistory":true,
         "issuerAccount":{"bankCode":"088","maskedAccountNo":"****-****-****-6547","linkedAccountId":null,"linked":false},
         "paymentDay":14,"alreadyRegistered":false}
    """.trimIndent()

    private val registerJson = """
        {"userCardId":21,"nickname":"생활비","issuer":{"code":"KB","name":"KB국민카드","color":"#FFBC00"},
         "productName":"KB국민 청춘대로 톡톡카드","cardType":"CREDIT","maskedNumber":"9410-****-****-1021","status":"ACTIVE",
         "isDefault":false,"linkedAccountId":5,
         "billing":{"paymentDay":14,"closingDayOffset":14,"settlementAccountId":5,"creditLimit":null},
         "initialPerformance":{"source":"SEED","prevMonth":{"yearMonth":"2026-10","recognizedAmount":120000,"benefitUsedAmount":null},"currentMonth":{"yearMonth":"2026-11","recognizedAmount":0,"benefitUsedAmount":null}},
         "registeredAt":"2026-11-03T01:00:00Z","imageUrl":null}
    """.trimIndent()

    @Test fun `카드 확인 요청 — 필드 이름 그대로`() {
        val body = fields(CardLookupRequest("9410123456785678", "08", "29"))
        assertEquals(setOf("cardNumber", "expiryMonth", "expiryYear"), body.keys)
        assertEquals("08", body["expiryMonth"]!!.jsonPrimitive.content)
    }

    @Test fun `카드 확인 요청을 글자로 찍어도 번호가 보이지 않는다`() {
        val text = CardLookupRequest("9410123456785678", "08", "29").toString()
        assertFalse(text.contains("9410123456785678"))
        assertTrue(text.contains("5678"))
    }

    @Test fun `카드 등록 요청 — 별칭이 없으면 칸을 보내지 않는다`() {
        assertEquals(setOf("verificationToken"), fields(CardRegisterRequest("cvt_1")).keys)
        assertEquals(setOf("verificationToken", "nickname"), fields(CardRegisterRequest("cvt_1", "생활비")).keys)
    }

    @Test fun `가입 후 계좌 연결 요청 — candidateIds만`() {
        val body = fields(MemberAccountLinkRequest(listOf("cand_1", "cand_2")))
        assertEquals(setOf("candidateIds"), body.keys)
        assertEquals(2, body["candidateIds"]!!.jsonArray.size)
    }

    @Test fun `카드 확인 응답 — 신용카드 · 계좌 미연결`() {
        val c = YamuJson.decodeFromString<CardLookupResponse>(lookupJson).toLookup()
        assertEquals("cvt_1", c.verificationToken)
        assertEquals("신한카드", c.brand.name)
        assertEquals(0xFF0046FFL, c.brand.colorArgb)
        assertEquals("신한카드 Mr.Life", c.productName)
        assertEquals("5678", c.last4)
        assertEquals(14, c.paymentDay)
        assertFalse(c.isCheck)
        assertEquals("신한은행 ·6547", c.issuerAccount?.label)
        assertTrue(c.accountMissing)
        assertFalse(c.blocked)
        assertFalse(c.registrable)
    }

    @Test fun `카드 확인 응답 — 체크카드는 결제일 · 마감 없음(null)`() {
        val json = """
            {"verificationToken":"cvt_2","expiresAt":"2026-11-03T01:05:00Z",
             "issuer":{"code":"HANA","name":"하나카드","color":null,"closingDayOffset":null},
             "product":{"cardProductId":7,"name":"트래블로그 체크카드","cardType":"CHECK","imageUrl":null},
             "maskedNumber":"9410-****-****-5527","holderMatched":true,"hasPriorHistory":false,
             "issuerAccount":{"bankCode":"999","maskedAccountNo":"****-****-****-1234","linkedAccountId":5,"linked":true},
             "paymentDay":null,"alreadyRegistered":false}
        """.trimIndent()
        val c = YamuJson.decodeFromString<CardLookupResponse>(json).toLookup()
        assertTrue(c.isCheck)
        assertNull(c.paymentDay)
        assertNull(c.brand.colorArgb)
        assertTrue(c.registrable)
    }

    @Test fun `명의가 다르거나 이미 등록한 카드는 등록을 막는다`() {
        val base = YamuJson.decodeFromString<CardLookupResponse>(lookupJson)
        assertTrue(base.copy(holderMatched = false).toLookup().blocked)
        assertTrue(base.copy(alreadyRegistered = true).toLookup().blocked)
    }

    @Test fun `모르는 은행 코드는 코드를 보이지 않고 결제 계좌로`() {
        assertEquals("결제 계좌 ·6547", IssuerAccount("777", "6547", false).label)
        assertEquals("결제 계좌 ·6547", IssuerAccount(null, "6547", false).label)
    }

    @Test fun `카드 등록 응답 — 시드 실적이 있으면 지난달 값`() {
        val c = YamuJson.decodeFromString<CardRegisterResponse>(registerJson).toRegistered()
        assertEquals(21L, c.userCardId)
        assertEquals("1021", c.last4)
        assertEquals(14, c.paymentDay)
        assertEquals(120_000L, c.prevMonthAmount)
    }

    @Test fun `카드 등록 응답 — 체크카드 · 실적 없음은 0이 아니라 null`() {
        val json = """
            {"userCardId":22,"nickname":"트래블로그","issuer":{"code":"HANA","name":"하나카드","color":"#008C8C"},
             "productName":"트래블로그 체크카드","cardType":"CHECK","maskedNumber":"9410-****-****-5527","status":"ACTIVE",
             "isDefault":false,"linkedAccountId":5,"billing":null,
             "initialPerformance":{"source":"NONE","prevMonth":null,"currentMonth":null},
             "registeredAt":"2026-11-03T01:00:00Z","imageUrl":null}
        """.trimIndent()
        val c = YamuJson.decodeFromString<CardRegisterResponse>(json).toRegistered()
        assertTrue(c.isCheck)
        assertNull(c.paymentDay)
        assertNull(c.prevMonthAmount)
    }

    @Test fun `계좌 후보 응답 — 은행 코드까지 읽는다`() {
        val r = YamuJson.decodeFromString<AccountCandidatesResponse>(
            """{"accounts":[{"candidateId":"cand_1","bank":{"code":"088","name":"신한은행"},"maskedAccountNo":"****-****-****-5678","accountName":null,"alreadyLinked":false}],"expiresAt":"2026-11-03T01:10:00Z"}""",
        )
        val c = r.accounts.single().toCandidate()
        assertEquals("cand_1", c.id)
        assertEquals("088", c.bankCode)
        assertEquals("5678", c.last4)
        assertEquals("신한은행", c.name)
    }

    @Test fun `계좌 연결 응답 — 연결 · 건너뜀을 합쳐 이 카드의 결제 계좌가 생겼는지 본다`() {
        val r = YamuJson.decodeFromString<AccountLinkResponse>(
            """{"accounts":[{"accountId":5,"bank":{"code":"088","name":"신한은행"},"accountName":null,"maskedAccountNo":"****-****-****-5678","isPrimary":true,"linkedAt":"2026-11-03T01:00:00Z"}],
                "skipped":[{"bank":{"code":"004","name":"국민은행"},"maskedAccountNo":"****-****-****-1234","reason":"ALREADY_LINKED"}],
                "verifiedBy":{"method":"PIN","auditLogId":9,"at":"2026-11-03T01:00:00Z"}}""",
        )
        assertEquals("PIN", r.verifiedBy?.method)
        val result = r.toResult()
        assertTrue(result.covers(IssuerAccount("088", "5678", false)))
        assertTrue(result.covers(IssuerAccount("004", "1234", false)))
        assertFalse(result.covers(IssuerAccount("081", "5678", false)))   // 끝자리가 같아도 다른 은행
    }

    @Test fun `계좌 연결 응답 — 가입 후에도 skipped 없이 온다`() {
        val r = YamuJson.decodeFromString<AccountLinkResponse>("""{"accounts":[]}""")
        assertTrue(r.skipped.isEmpty())
        assertNull(r.verifiedBy)
    }

    @Test fun `카드 목록 — 상태 값을 읽고 없어도 된다`() {
        val full = YamuJson.decodeFromString<CardsResponse>(
            """{"cards":[{"userCardId":1,"nickname":"a","issuer":{"name":"신한카드"},"productName":"p","cardType":"CREDIT","maskedNumber":"9410-****-****-5678",
                "status":"INACTIVE","issuerStatus":"SUSPENDED","isDefault":false,"mainBenefits":[],"performance":null,"billingSummary":null,"imageUrl":"https://cdn/a.webp"}],"dataSyncedAt":null}""",
        ).cards.single()
        assertEquals("INACTIVE", full.status)
        assertEquals("https://cdn/a.webp", full.imageUrl)
        assertEquals("카드사 정지", full.toLinked().stateLabel)
        val old = YamuJson.decodeFromString<CardDto>("""{"userCardId":1,"issuer":{"name":"신한카드"},"productName":"p","maskedNumber":"1234"}""")
        assertNull(old.status)
        assertNull(old.toLinked().stateLabel)
    }

    @Test fun `카드 상태 태그`() {
        assertEquals("유효기간 만료", cardStateLabel("ACTIVE", "EXPIRED"))
        assertEquals("추천 제외", cardStateLabel("INACTIVE", "NORMAL"))
        assertNull(cardStateLabel("ACTIVE", "NORMAL"))
        assertNull(cardStateLabel(null, null))
    }
}

/** 실제 경로 · 헤더 — Retrofit으로 MockWebServer에 보내 본다 */
class LinkApiRequestTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: LinkRepositoryImpl

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        repository = LinkRepositoryImpl(server.retrofit().create(LinkApi::class.java))
    }

    @After fun tearDown() = server.close()

    private fun respond(code: Int, body: String) = server.enqueue(json(code, body))

    @Test fun `카드 확인 — 본인 인증 없이 번호 · 기간을 본문으로만 보낸다`() = runTest {
        respond(
            200,
            """{"success":true,"data":{"verificationToken":"cvt_1","issuer":{"name":"신한카드"},"product":{"name":"Mr.Life"},"maskedNumber":"9410-****-****-5678"}}""",
        )
        val r = repository.lookupCard("9410123456785678", "08", "29")
        assertEquals("cvt_1", (r as ApiResult.Success).data.verificationToken)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/cards/lookup", request.url.encodedPath)      // 경로 · 쿼리에 번호가 없다
        assertNull(request.headers[YamuHeaders.STEP_UP_TOKEN])
        assertTrue(request.body?.utf8().orEmpty().contains("\"cardNumber\":\"9410123456785678\""))
    }

    @Test fun `카드 등록 — 201을 받고 본인 인증 토큰은 헤더로, 멱등 키는 없음`() = runTest {
        respond(
            201,
            """{"success":true,"data":{"userCardId":21,"issuer":{"name":"KB국민카드"},"productName":"톡톡","maskedNumber":"9410-****-****-1021"}}""",
        )
        val r = repository.registerCard("cvt_1", null, "su_1")
        assertEquals(21L, (r as ApiResult.Success).data.userCardId)
        val request = server.takeRequest()
        assertEquals("/cards", request.url.encodedPath)
        assertEquals("su_1", request.headers[YamuHeaders.STEP_UP_TOKEN])
        assertNull(request.headers[YamuHeaders.IDEMPOTENCY_KEY])
        assertEquals("""{"verificationToken":"cvt_1"}""", request.body?.utf8().orEmpty())
    }

    @Test fun `결제 계좌 미연결 409 — details로 어느 계좌인지 안다`() = runTest {
        respond(
            409,
            """{"success":false,"error":{"code":"CARD_ACCOUNT_NOT_LINKED","message":"…","details":{"bankCode":"088","accountLast4":"6547"}}}""",
        )
        val error = (repository.registerCard("cvt_1", null, "su_1") as ApiResult.Failure).error
        assertTrue(error is ApiError.Conflict)
        assertEquals("CARD_ACCOUNT_NOT_LINKED", error.code)
        assertEquals("088", error.detailString("bankCode"))
        assertEquals("6547", error.detailString("accountLast4"))
    }

    @Test fun `계좌 후보 — 회원 경로는 토큰을 직접 붙이지 않는다`() = runTest {
        respond(200, """{"success":true,"data":{"accounts":[],"expiresAt":"2026-11-03T01:10:00Z"}}""")
        assertTrue((repository.accountCandidates() as ApiResult.Success).data.isEmpty())
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/accounts/candidates", request.url.encodedPath)
        assertNull(request.headers[YamuHeaders.AUTHORIZATION])   // 인증 클라이언트의 인터셉터가 붙인다
    }

    @Test fun `계좌 연결 — 멱등 키 · 본인 인증 토큰 헤더와 candidateIds`() = runTest {
        respond(201, """{"success":true,"data":{"accounts":[],"skipped":[],"verifiedBy":null}}""")
        repository.linkAccounts(listOf("cand_1"), "key-12345678", "su_2")
        val request = server.takeRequest()
        assertEquals("/accounts", request.url.encodedPath)
        assertEquals("key-12345678", request.headers[YamuHeaders.IDEMPOTENCY_KEY])
        assertEquals("su_2", request.headers[YamuHeaders.STEP_UP_TOKEN])
        assertEquals("""{"candidateIds":["cand_1"]}""", request.body?.utf8().orEmpty())
    }
}
