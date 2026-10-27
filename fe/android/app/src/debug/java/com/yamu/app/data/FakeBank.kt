package com.yamu.app.data

import com.yamu.app.core.format.KST
import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.security.SessionStore
import com.yamu.app.core.session.DevSession
import com.yamu.app.core.session.HomeScenario
import com.yamu.app.core.session.LinkScenario
import com.yamu.app.feature.link.data.AccountAsset
import com.yamu.app.feature.link.data.BillLine
import com.yamu.app.feature.link.data.CardAsset
import com.yamu.app.feature.link.data.CashflowCandidate
import com.yamu.app.feature.link.data.CashflowDraft
import com.yamu.app.feature.link.data.CashflowPlan
import com.yamu.app.feature.link.data.CashflowRepository
import com.yamu.app.feature.link.data.Institution
import com.yamu.app.feature.link.data.InstitutionType
import com.yamu.app.feature.link.data.LinkAssets
import com.yamu.app.feature.link.data.LinkCompletion
import com.yamu.app.feature.link.data.LinkPurpose
import com.yamu.app.feature.link.data.LinkRepository
import com.yamu.app.feature.link.data.LinkSession
import com.yamu.app.feature.link.data.LinkedAccount
import com.yamu.app.feature.link.data.LinkedCard
import com.yamu.app.feature.link.data.LinkedOverview
import com.yamu.app.feature.link.data.OneWonSent
import com.yamu.app.feature.link.data.OwnAccount
import com.yamu.app.feature.link.data.Terms
import com.yamu.app.feature.link.data.brandOf
import kotlinx.coroutines.delay
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * debug 전용 가짜 은행 · 카드사 — 기관 목록 · 내 명의 자산 · 연결 상태를 메모리에 둔다(09 문서 5-6).
 * 숫자는 프로토타입 C-01 · C-03 · C-04 · C-12와 같다. 앱을 다시 켜면 '초기 설정 끝' 여부로 다시 채운다.
 *
 * 시험값: 1원 숫자 1004 · '0000'은 시간 지남 · 그 밖은 틀림(5번째 틀리면 1원 다시 받기)
 */
@Singleton
class FakeBank @Inject constructor(private val sessionStore: SessionStore) {

    private data class Inst(val id: String, val name: String, val type: InstitutionType, val color: String?, val mine: Boolean)

    private val institutions = listOf(
        Inst("BANK_KB", "KB국민은행", InstitutionType.BANK, "#FFBC00", true),
        Inst("CARD_SHINHAN", "신한카드", InstitutionType.CARD, "#0046FF", true),
        Inst("CARD_KB", "KB국민카드", InstitutionType.CARD, "#FFBC00", true),
        Inst("CARD_WOORI", "우리카드", InstitutionType.CARD, "#0083CA", true),
        Inst("CARD_NH", "NH농협카드", InstitutionType.CARD, "#00A651", true),
        Inst("CARD_HANA", "하나카드", InstitutionType.CARD, "#008C8C", true),
        Inst("BANK_SHINHAN", "신한은행", InstitutionType.BANK, null, false),
        Inst("BANK_HANA", "하나은행", InstitutionType.BANK, null, false),
        Inst("BANK_WOORI", "우리은행", InstitutionType.BANK, null, false),
        Inst("BANK_NH", "NH농협은행", InstitutionType.BANK, null, false),
        Inst("CARD_SAMSUNG", "삼성카드", InstitutionType.CARD, null, false),
        Inst("CARD_HYUNDAI", "현대카드", InstitutionType.CARD, null, false),
        Inst("CARD_LOTTE", "롯데카드", InstitutionType.CARD, null, false),
        Inst("CARD_BC", "BC카드", InstitutionType.CARD, null, false),
    )

    private data class Account(val assetId: String, val inst: String, val name: String, val last4: String, val balance: Long)

    private data class Card(
        val assetId: String,
        val inst: String,
        val product: String,
        val last4: String,
        val paymentDay: Int?,
        val check: Boolean = false,
        val newlyIssued: Boolean = false,   // 가입 뒤 새로 나온 카드(C-12에서만 보임)
        val recognized: Long = 0,
        val color: String? = null,          // 상품 색(체크 = 회갈색) — 없으면 카드사 색. 실적 · 결제 가짜 값과 같게
    )

    private val accounts = listOf(Account("A1", "BANK_KB", "입출금", "1234", 950_000))

    private val cards = listOf(
        Card("C1", "CARD_SHINHAN", "신한 생활 신용카드", "1021", 14, recognized = 120_000),
        Card("C2", "CARD_KB", "KB국민 적립 신용카드", "3310", 14, recognized = 210_000),
        Card("C3", "CARD_WOORI", "우리 통신·렌탈 신용카드", "7782", 14, recognized = 320_000),
        Card("C4", "CARD_NH", "NH농협 교통·쇼핑 신용카드", "4410", 15, recognized = 80_000),
        Card("C5", "CARD_HANA", "하나 마트·주유 신용카드", "5096", 20, recognized = 150_000),
        Card("C6", "CARD_KB", "KB국민 체크카드", "8823", null, check = true, recognized = 60_000, color = "#60584C"),
        Card("C7", "CARD_SHINHAN", "트래블 마일 신용카드", "5527", 14, newlyIssued = true),
    )

    private class Session(val id: String, val purpose: String, val institutionIds: List<String>, var verified: Boolean = false)

    private val sessions = mutableMapOf<String, Session>()
    private val linked = linkedSetOf<String>()        // 연결된 assetId
    private var ownAccountVerified = false
    private var oneWonSent = false
    private var wrongCodes = 0
    private var loaded = false
    private val linkedAt = OffsetDateTime.now(KST).truncatedTo(ChronoUnit.MINUTES).toString()

    /** 앱을 다시 켰을 때: 초기 설정이 끝난 사용자면 6장 모두 연결된 상태로 */
    private suspend fun ensureLoaded() {
        if (loaded) return
        loaded = true
        if (sessionStore.read().onboardingCompleted) linkAll(updateHome = false)
    }

    /** 디버그 '로그인 · 연결 끝' · done… 로그인 — 가짜 홈도 '정상'으로 */
    fun linkAll(updateHome: Boolean = true) {
        if (updateHome && DevSession.homeScenario in setOf(HomeScenario.NOT_LINKED, HomeScenario.FIRST_DAY)) DevSession.homeScenario = HomeScenario.NORMAL
        loaded = true
        ownAccountVerified = true
        linked.clear()
        linked += accounts.map { it.assetId } + cards.filterNot { it.newlyIssued }.map { it.assetId }
    }

    /** 새로 가입 · 연결 전 — 가짜 홈도 '연결 전'으로(연결을 마치면 '가입 첫날') */
    fun reset() {
        DevSession.homeScenario = HomeScenario.NOT_LINKED
        loaded = true
        ownAccountVerified = false
        oneWonSent = false
        wrongCodes = 0
        linked.clear()
        sessions.clear()
    }

    private fun Inst.toInstitution() = Institution(
        id = id,
        brand = brandOf(name, color = color),
        type = type,
        hasMyProducts = mine && DevSession.linkScenario != LinkScenario.NO_PRODUCTS,
        linkedCount = (accounts.filter { it.inst == id }.map { it.assetId } + cards.filter { it.inst == id }.map { it.assetId }).count { it in linked },
        selectable = true,
    )

    private fun inst(id: String) = institutions.first { it.id == id }

    private val terms = listOf(
        Terms("FIN_INFO", "1.0", "금융 정보 조회 · 제공", true),
        Terms("AUTO_DEBIT", "1.0", "카드대금 자동이체 · 확정된 청구액만 출금해요", true),
        Terms("PRIVACY", "1.0", "개인정보 처리", true),
    )

    private fun Session.toModel(): LinkSession {
        val needsCheck = purpose == LinkPurpose.ONBOARDING && "BANK_KB" in institutionIds
        val verifiedAlready = ownAccountVerified || DevSession.linkScenario == LinkScenario.ALREADY_VERIFIED
        return LinkSession(
            id = id,
            status = if (verified) "VERIFIED" else "SELECTED",
            institutionCount = institutionIds.size,
            ownAccount = if (needsCheck) OwnAccount(required = true, bankName = "KB국민은행", last4 = "1234", verified = verifiedAlready) else null,
            terms = if (purpose == LinkPurpose.ONBOARDING) terms else emptyList(),
        )
    }

    // ── LinkRepository가 부르는 것 ──

    suspend fun institutions(type: InstitutionType?): ApiResult<List<Institution>> {
        ensureLoaded()
        delay(400)
        return ApiResult.Success(institutions.filter { type == null || it.type == type }.map { it.toInstitution() })
    }

    suspend fun startSession(ids: List<String>, purpose: String): ApiResult<LinkSession> {
        ensureLoaded()
        delay(400)
        val s = Session("ls_${sessions.size + 1}", purpose, ids)
        sessions[s.id] = s
        return ApiResult.Success(s.toModel())
    }

    suspend fun session(id: String): ApiResult<LinkSession> {
        delay(200)
        val s = sessions[id] ?: return ApiResult.Failure(ApiError.NotFound("LINK_SESSION_NOT_FOUND"))
        return ApiResult.Success(s.toModel())
    }

    suspend fun sendOneWon(id: String): ApiResult<OneWonSent> {
        delay(700)
        if (sessions[id] == null) return ApiResult.Failure(ApiError.NotFound("LINK_SESSION_NOT_FOUND"))
        oneWonSent = true
        wrongCodes = 0
        val expires = OffsetDateTime.now(KST).plusMinutes(10).toString()
        return ApiResult.Success(OneWonSent(prefix = "YAMU", codeLength = 4, expiresAtIso = expires))
    }

    suspend fun verify(id: String, authCode: String?): ApiResult<Unit> {
        delay(500)
        val s = sessions[id] ?: return ApiResult.Failure(ApiError.NotFound("LINK_SESSION_NOT_FOUND"))
        val model = s.toModel()
        if (model.ownAccount?.let { it.required && !it.verified } == true) {
            when (authCode) {
                "1004" -> Unit
                "0000" -> return ApiResult.Failure(ApiError.InvalidRequest("AUTH_CODE_EXPIRED"))
                else -> {
                    wrongCodes++
                    return ApiResult.Failure(ApiError.InvalidRequest("AUTH_CODE_MISMATCH", buildJsonObject { put("remainingAttempts", 5 - wrongCodes) }))
                }
            }
            ownAccountVerified = true
        }
        s.verified = true
        return ApiResult.Success(Unit)
    }

    suspend fun assets(id: String): ApiResult<LinkAssets> {
        delay(1_200)   // '찾고 있어요'가 잠깐 보이게
        val s = sessions[id] ?: return ApiResult.Failure(ApiError.NotFound("LINK_SESSION_NOT_FOUND"))
        if (DevSession.linkScenario == LinkScenario.NO_PRODUCTS) return ApiResult.Success(LinkAssets(emptyList(), emptyList()))
        val inIds = s.institutionIds.toSet()
        val acc = accounts.filter { it.inst in inIds && s.purpose == LinkPurpose.ONBOARDING }.map {
            AccountAsset(it.assetId, brandOf(inst(it.inst).name, color = inst(it.inst).color), it.name, it.last4, suggestedPrimary = true)
        }
        val crd = cards.filter { it.inst in inIds && (s.purpose == LinkPurpose.CARD_ADD || !it.newlyIssued) }.map {
            val i = inst(it.inst)
            CardAsset(it.assetId, brandOf(i.name, color = it.color ?: i.color), it.product, it.check, it.last4, it.paymentDay, hasPriorHistory = !it.newlyIssued, alreadyRegistered = it.assetId in linked)
        }
        return ApiResult.Success(LinkAssets(acc, crd))
    }

    suspend fun complete(id: String, accountIds: List<String>, cardIds: List<String>): ApiResult<LinkCompletion> {
        delay(900)
        val s = sessions[id] ?: return ApiResult.Failure(ApiError.NotFound("LINK_SESSION_NOT_FOUND"))
        if (DevSession.linkScenario == LinkScenario.BANK_ERROR) return ApiResult.Failure(ApiError.BankUnavailable("BANK_TIMEOUT"))
        if (s.purpose == LinkPurpose.ONBOARDING && !s.verified) return ApiResult.Failure(ApiError.StepUpRequired("STEP_UP_REQUIRED"))
        linked += accountIds + cardIds
        if (DevSession.homeScenario == HomeScenario.NOT_LINKED) DevSession.homeScenario = HomeScenario.FIRST_DAY
        return ApiResult.Success(
            LinkCompletion(
                accountIds = accountIds.map { a -> accounts.indexOfFirst { it.assetId == a } + 1L },
                userCardIds = cardIds.map { c -> cards.indexOfFirst { it.assetId == c } + 11L },
            ),
        )
    }

    suspend fun overview(): ApiResult<LinkedOverview> {
        ensureLoaded()
        delay(400)
        val acc = accounts.filter { it.assetId in linked }.mapIndexed { i, a ->
            val institution = inst(a.inst)
            LinkedAccount(i + 1L, brandOf(institution.name, color = institution.color), a.name, a.last4, a.balance, linkedAt, isPrimary = i == 0)
        }
        val crd = cards.filter { it.assetId in linked }.map { c ->
            val institution = inst(c.inst)
            LinkedCard(cards.indexOf(c) + 11L, brandOf(institution.name, color = c.color ?: institution.color), c.product, c.check, c.last4, c.paymentDay, c.recognized, java.time.YearMonth.now(KST).toString(), isDefault = c.assetId == "C2")
        }
        return ApiResult.Success(LinkedOverview(acc, crd, linkedAt))
    }
}

/** debug 전용 가짜 연결 저장소 — 실제 흐름(세션 → 1원 → 서명 → 목록 → 확정)을 그대로 흉내 */
@Singleton
class FakeLinkRepository @Inject constructor(private val bank: FakeBank) : LinkRepository {
    override suspend fun institutions(type: InstitutionType?) = withFault { bank.institutions(type) }
    override suspend fun startSession(institutionIds: List<String>, purpose: String) = bank.startSession(institutionIds, purpose)
    override suspend fun session(id: String) = bank.session(id)
    override suspend fun sendOneWon(id: String) = bank.sendOneWon(id)
    override suspend fun verify(id: String, stepUpToken: String, authCode: String?, agreements: List<Terms>) = bank.verify(id, authCode)
    override suspend fun assets(id: String) = bank.assets(id)
    override suspend fun complete(
        id: String,
        accountAssetIds: List<String>,
        cardAssetIds: List<String>,
        primaryAccountAssetId: String?,
        idempotencyKey: String,
        stepUpToken: String?,
    ) = bank.complete(id, accountAssetIds, cardAssetIds)
    override suspend fun linkedOverview(refresh: Boolean) = withFault(empty = { ApiResult.Success(LinkedOverview(emptyList(), emptyList(), null)) }) { bank.overview() }
}

/** debug 전용 가짜 월급 · 고정 지출 — 프로토타입 B-06 숫자(월급 10일 2,800,000 · 월세 · 통신비 · 구독료 + 하나카드 대금) */
@Singleton
class FakeCashflowRepository @Inject constructor() : CashflowRepository {
    var lastPlan: CashflowPlan? = null
        private set

    override suspend fun draft(): ApiResult<CashflowDraft> {
        delay(600)
        return ApiResult.Success(
            CashflowDraft(
                income = CashflowCandidate(301, isIncome = true, title = "월급", amount = 2_800_000, dayOfMonth = 10),
                expenses = listOf(
                    CashflowCandidate(302, false, "월세", 250_000, 25),
                    CashflowCandidate(303, false, "통신비", 35_000, 27),
                    CashflowCandidate(304, false, "구독료", 15_000, 1),
                ),
                bills = listOf(BillLine("하나카드 대금", 150_000, 20)),
                dailyLivingCost = 6_000,
            ),
        )
    }

    override suspend fun confirm(plan: CashflowPlan): ApiResult<Unit> {
        delay(800)
        lastPlan = plan
        return ApiResult.Success(Unit)
    }
}
