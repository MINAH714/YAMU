package com.yamu.app.data

import com.yamu.app.core.format.KST
import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.security.SessionStore
import com.yamu.app.core.session.DevSession
import com.yamu.app.core.session.FaultScenario
import com.yamu.app.core.session.HomeScenario
import com.yamu.app.core.session.LinkScenario
import com.yamu.app.feature.auth.data.AccountCandidate
import com.yamu.app.feature.link.data.AccountLinkResult
import com.yamu.app.feature.link.data.AccountRef
import com.yamu.app.feature.link.data.BillLine
import com.yamu.app.feature.link.data.CardInfo
import com.yamu.app.feature.link.data.CardLookup
import com.yamu.app.feature.link.data.CashflowCandidate
import com.yamu.app.feature.link.data.CashflowDraft
import com.yamu.app.feature.link.data.CashflowPlan
import com.yamu.app.feature.link.data.CashflowRepository
import com.yamu.app.feature.forecast.data.CashflowCreateRequest
import com.yamu.app.feature.link.data.IncomeAccount
import com.yamu.app.feature.link.data.IssuerAccount
import com.yamu.app.feature.link.data.LinkRepository
import com.yamu.app.feature.link.data.LinkedAccount
import com.yamu.app.feature.link.data.LinkedCard
import com.yamu.app.feature.link.data.LinkedOverview
import com.yamu.app.feature.link.data.NextWithdrawal
import com.yamu.app.feature.link.data.RegisteredCard
import com.yamu.app.feature.link.data.brandOf
import com.yamu.app.feature.link.data.cardStateLabel
import kotlinx.coroutines.delay
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * debug 전용 가짜 은행 · 카드사 — 내 명의 계좌 · 카드와 등록 상태를 메모리에 둔다.
 * 앱을 다시 켜면 '초기 설정 끝' 여부로 다시 채운다.
 *
 * 시험값(카드 번호 9410 0000 0000 + 끝 4자리): 아래 [cards]의 끝자리는 내 카드 · 0001 카드사 만료 · 0002 카드사 정지 ·
 * 0003 이미 등록 · 그 밖은 없는 카드. 5096 Mr.Life는 결제 계좌(신한 ·5678)를 먼저 연결해야 한다. 등록 단계 오류는 디버그 메뉴 '연결 상태'.
 * 7782 LOCA LIKIT은 출금 전 청구가 있어 해제할 수 없다.
 */
@Singleton
class FakeBank @Inject constructor(private val sessionStore: SessionStore) {

    private data class Account(val assetId: String, val bankCode: String, val bank: String, val name: String, val last4: String, val balance: Long)

    private data class Card(
        val assetId: String,
        val issuer: String,
        val issuerColor: String,
        val product: String,
        val last4: String,
        val paymentDay: Int?,
        val check: Boolean = false,
        val newlyIssued: Boolean = false,   // 가입 뒤 새로 나온 카드 — 이전 거래가 없음
        val recognized: Long = 0,
        val color: String? = null,          // 상품 색(체크 = 회갈색) — 없으면 카드사 색. 실적 · 결제 가짜 값과 같게
        val accountId: String = "A1",       // 카드사가 정한 출금 계좌
    ) {
        val number: String get() = NUMBER_PREFIX + last4
    }

    private class Issued(val cardId: String, val expiresAt: Instant)

    private val accounts = listOf(
        Account("A1", "004", "KB국민은행", "입출금", "1234", 950_000),
        Account("A2", "088", "신한은행", "신한 주거래 통장", "5678", 320_000),
    )

    // BE 카드 시드(be/seed 01 · 02)에 있는 상품만 — 상품 그림(CardImages)이 있는 것 위주
    private val cards = listOf(
        Card("C1", "KB국민카드", "#FFBC00", "KB국민 청춘대로 톡톡카드", "1021", 14, recognized = 120_000),
        Card("C2", "현대카드", "#1A1A1A", "현대카드ZERO Edition3(포인트형)", "3310", 14, recognized = 210_000),
        Card("C3", "롯데카드", "#E60012", "LOCA LIKIT Shop", "7782", 14, recognized = 320_000),
        Card("C4", "삼성카드", "#1428A0", "삼성 iD ON 카드", "4410", 15, recognized = 80_000),
        Card("C5", "신한카드", "#0046FF", "신한카드 Mr.Life", "5096", 20, recognized = 150_000, accountId = "A2"),
        Card("C6", "KB국민카드", "#FFBC00", "KB국민 노리2 체크카드(KB Pay)", "8823", null, check = true, recognized = 60_000, color = "#60584C"),
        Card("C7", "하나카드", "#008C8C", "하나카드 트래블로그 체크카드", "5527", null, check = true, newlyIssued = true),
    )

    private val linked = linkedSetOf("A1")            // 연결된 계좌 · 등록된 카드 assetId. 가입 때 1원으로 계좌 하나는 연결돼 있다
    private val tokens = mutableMapOf<String, Issued>()
    private var tokenSeq = 0
    private var candidateGeneration = 0
    private var candidateExpiryServed = false
    private val linkResults = mutableMapOf<String, Pair<List<String>, AccountLinkResult>>()   // 멱등 키 → 처음 요청 · 결과
    private val nicknames = mutableMapOf<String, String>()
    private val accountAliases = mutableMapOf<String, String>()
    private var primaryAccount = "A1"
    private val excluded = mutableSetOf<String>()     // 자동 선택에서 뺀 카드
    private var defaultCard: String? = "C2"
    private var order: List<String> = emptyList()     // 저장한 카드 순서(assetId)
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
        linked.clear()
        linked += listOf("A1") + cards.filterNot { it.newlyIssued }.map { it.assetId }
    }

    /** 새로 가입 · 연결 전 — 가짜 홈도 '연결 전'으로(카드를 등록하면 '가입 첫날') */
    fun reset() {
        DevSession.homeScenario = HomeScenario.NOT_LINKED
        loaded = true
        linked.clear()
        linked += "A1"
        tokens.clear()
        linkResults.clear()
        candidateExpiryServed = false
        nicknames.clear()
        accountAliases.clear()
        primaryAccount = "A1"
        excluded.clear()
        defaultCard = "C2"
        order = emptyList()
    }

    private fun account(id: String) = accounts.first { it.assetId == id }

    private fun Card.brand() = brandOf(issuer, color = color ?: issuerColor)

    // ── LinkRepository가 부르는 것 ──

    /** BE CardRegistrationService.lookup과 같은 순서 — 기간 → 없는 카드 → 이미 등록 → 카드사 정지 · 만료 */
    suspend fun lookup(number: String, month: String, year: String): ApiResult<CardLookup> {
        ensureLoaded()
        delay(600)
        if (YearMonth.of(2000 + year.toInt(), month.toInt()).isBefore(YearMonth.now(KST))) return verificationFailed("EXPIRY_PASSED")
        when (number) {
            NUMBER_PREFIX + "0001" -> return verificationFailed("ISSUER_EXPIRED")
            NUMBER_PREFIX + "0002" -> return verificationFailed("ISSUER_SUSPENDED")
            NUMBER_PREFIX + "0003" -> return ApiResult.Failure(ApiError.Conflict("CARD_ALREADY_REGISTERED"))
        }
        val card = cards.firstOrNull { it.number == number } ?: return ApiResult.Failure(ApiError.NotFound("CARD_NOT_FOUND"))
        if (card.assetId in linked) return ApiResult.Failure(ApiError.Conflict("CARD_ALREADY_REGISTERED"))
        val token = "cvt_${++tokenSeq}"
        tokens[token] = Issued(card.assetId, Instant.now().plus(5, ChronoUnit.MINUTES))
        val account = account(card.accountId)
        // '등록 때 계좌 미연결' 상태면 확인 때는 연결된 것처럼 보이고 등록에서 409가 난다(그 사이 연결을 끊은 경우)
        val shownLinked = account.assetId in linked || DevSession.linkScenario == LinkScenario.ACCOUNT_NOT_LINKED
        return ApiResult.Success(
            CardLookup(
                verificationToken = token,
                brand = card.brand(),
                productName = card.product,
                isCheck = card.check,
                maskedNumber = "${number.take(4)}-****-****-${number.takeLast(4)}",
                last4 = card.last4,
                paymentDay = card.paymentDay,
                hasPriorHistory = !card.newlyIssued,
                holderMatched = true,
                alreadyRegistered = false,
                issuerAccount = IssuerAccount(account.bankCode, account.last4, shownLinked),
            ),
        )
    }

    /** BE CardRegistrationService.register와 같은 순서 — 토큰 → 계좌 연결 → 토큰 소비. 계좌 때문에 실패하면 토큰이 남는다 */
    suspend fun register(verificationToken: String): ApiResult<RegisteredCard> {
        delay(700)
        val issued = tokens[verificationToken]
        if (issued == null || Instant.now().isAfter(issued.expiresAt) || DevSession.linkScenario == LinkScenario.TOKEN_EXPIRED) {
            return ApiResult.Failure(ApiError.InvalidRequest("VERIFICATION_TOKEN_EXPIRED"))
        }
        val card = cards.first { it.assetId == issued.cardId }
        if (card.assetId in linked) return ApiResult.Failure(ApiError.Conflict("CARD_ALREADY_REGISTERED"))
        val account = account(card.accountId)
        if (account.assetId !in linked) {
            return ApiResult.Failure(
                ApiError.Conflict(
                    "CARD_ACCOUNT_NOT_LINKED",
                    buildJsonObject {
                        put("bankCode", account.bankCode)
                        put("accountLast4", account.last4)
                    },
                ),
            )
        }
        tokens.remove(verificationToken)
        linked += card.assetId
        if (DevSession.homeScenario == HomeScenario.NOT_LINKED) DevSession.homeScenario = HomeScenario.FIRST_DAY
        return ApiResult.Success(
            RegisteredCard(
                userCardId = cards.indexOf(card) + 11L,
                brand = card.brand(),
                productName = card.product,
                isCheck = card.check,
                last4 = card.last4,
                paymentDay = card.paymentDay,
                prevMonthAmount = card.recognized.takeUnless { card.newlyIssued },
            ),
        )
    }

    /** 가입 후 목록 — 아직 연결 안 한 계좌만. 목록을 새로 받으면 이전 candidateId는 쓸 수 없다 */
    suspend fun candidates(): ApiResult<List<AccountCandidate>> {
        ensureLoaded()
        delay(500)
        candidateGeneration++
        return ApiResult.Success(
            accounts.filter { it.assetId !in linked }.map {
                AccountCandidate(candidateId(it), brandOf(it.bank), it.name, it.last4, alreadyLinked = false, bankCode = it.bankCode)
            },
        )
    }

    private fun candidateId(account: Account) = "cand_${candidateGeneration}_${account.assetId}"

    /** 같은 키면 다시 연결하지 않고 처음 결과, 같은 키로 다른 계좌면 409(BE AccountLinkService.link) */
    suspend fun linkAccounts(candidateIds: List<String>, idempotencyKey: String): ApiResult<AccountLinkResult> {
        delay(800)
        linkResults[idempotencyKey]?.let { (ids, result) ->
            return if (ids == candidateIds) ApiResult.Success(result) else ApiResult.Failure(ApiError.Conflict("IDEMPOTENCY_KEY_REUSED"))
        }
        if (DevSession.linkScenario == LinkScenario.BANK_ERROR) return ApiResult.Failure(ApiError.BankUnavailable("FIN_API_UNAVAILABLE"))
        if (DevSession.linkScenario == LinkScenario.CANDIDATE_EXPIRED && !candidateExpiryServed) {
            candidateExpiryServed = true
            return ApiResult.Failure(ApiError.InvalidRequest("ACCOUNT_CANDIDATE_EXPIRED"))
        }
        val selected = accounts.filter { candidateId(it) in candidateIds }
        if (selected.size != candidateIds.size) return ApiResult.Failure(ApiError.InvalidRequest("ACCOUNT_CANDIDATE_EXPIRED"))
        linked += selected.map { it.assetId }
        val result = AccountLinkResult(selected.map { AccountRef(it.bankCode, it.last4) })
        linkResults[idempotencyKey] = candidateIds to result
        return ApiResult.Success(result)
    }

    suspend fun overview(): ApiResult<LinkedOverview> {
        ensureLoaded()
        delay(400)
        val acc = linkedAccounts().map { it.toLinked() }
        val crd = linkedCards().map { c ->
            LinkedCard(
                c.userCardId(), c.brand(), c.product, c.check, c.last4, c.paymentDay, c.recognized, YearMonth.now(KST).toString(),
                isDefault = c.assetId == defaultCard,
                stateLabel = cardStateLabel(if (c.assetId in excluded) "INACTIVE" else "ACTIVE", "NORMAL"),
                nickname = nicknames[c.assetId],
                recommended = c.assetId !in excluded,
            )
        }
        return ApiResult.Success(LinkedOverview(acc, crd, linkedAt))
    }

    private fun linkedAccounts() = accounts.filter { it.assetId in linked }

    private fun Account.toLinked() = LinkedAccount(
        accountId = linkedAccounts().indexOf(this) + 1L,
        brand = brandOf(bank),
        name = accountAliases[assetId] ?: name,
        last4 = last4,
        balance = balance,
        balanceAsOfIso = linkedAt,
        isPrimary = assetId == primaryAccount,
        alias = accountAliases[assetId],
        cardCount = cards.count { it.assetId in linked && it.accountId == assetId },
    )

    suspend fun linkedAccountList(): ApiResult<List<LinkedAccount>> {
        ensureLoaded()
        delay(300)
        return ApiResult.Success(linkedAccounts().map { it.toLinked() })
    }

    /** BE AccountManagementService.update와 같게 */
    suspend fun updateAccount(accountId: Long, alias: String?, makePrimary: Boolean): ApiResult<LinkedAccount> {
        delay(300)
        if (alias != null && alias.length > 20) return ApiResult.Failure(ApiError.InvalidRequest("INVALID_REQUEST"))
        val account = linkedAccounts().getOrNull((accountId - 1).toInt()) ?: return ApiResult.Failure(ApiError.NotFound("RESOURCE_NOT_FOUND"))
        alias?.let { if (it.isBlank()) accountAliases.remove(account.assetId) else accountAliases[account.assetId] = it.trim() }
        if (makePrimary) primaryAccount = account.assetId
        return ApiResult.Success(account.toLinked())
    }

    private fun Card.userCardId() = cards.indexOf(this) + 11L

    /** 저장한 순서가 먼저, 나중에 등록한 카드는 뒤에 */
    private fun linkedCards(): List<Card> =
        cards.filter { it.assetId in linked }.sortedBy { c -> order.indexOf(c.assetId).takeIf { it >= 0 } ?: Int.MAX_VALUE }

    private fun linkedCard(userCardId: Long): Card? = cards.firstOrNull { it.userCardId() == userCardId && it.assetId in linked }

    private fun Card.toInfo(): CardInfo {
        val account = account(accountId)
        return CardInfo(
            userCardId = userCardId(),
            brand = brand(),
            nickname = nicknames[assetId],
            productName = product,
            isCheck = check,
            last4 = last4,
            recommended = assetId !in excluded,
            stateLabel = cardStateLabel(if (assetId in excluded) "INACTIVE" else "ACTIVE", "NORMAL"),
            issuerBlocked = false,
            isDefault = assetId == defaultCard,
            paymentDay = paymentDay,
            settlementAccountId = if (check) null else accounts.filter { it.assetId in linked }.indexOf(account).takeIf { it >= 0 }?.let { it + 1L },
            checkAccountLabel = if (check) "${account.bank} ·${account.last4}" else null,
            nextWithdrawal = if (assetId == "C3") NextWithdrawal(recognized, LocalDate.now(KST).plusDays(10)) else null,
            hasOpenBill = assetId == "C3",
        )
    }

    suspend fun cardDetail(userCardId: Long): ApiResult<CardInfo> {
        ensureLoaded()
        delay(300)
        return linkedCard(userCardId)?.let { ApiResult.Success(it.toInfo()) } ?: ApiResult.Failure(ApiError.NotFound("RESOURCE_NOT_FOUND"))
    }

    /** BE CardManagementService.update와 같게 — 추천에서 빼면 기본 지정이 풀리고, 빠진 카드는 기본이 될 수 없다 */
    suspend fun updateCard(userCardId: Long, nickname: String?, recommended: Boolean?, isDefault: Boolean?): ApiResult<CardInfo> {
        delay(300)
        val card = linkedCard(userCardId) ?: return ApiResult.Failure(ApiError.NotFound("RESOURCE_NOT_FOUND"))
        val staysExcluded = recommended?.not() ?: (card.assetId in excluded)
        if (isDefault == true && staysExcluded) return ApiResult.Failure(ApiError.InvalidRequest("INVALID_REQUEST"))
        nickname?.let { if (it.isBlank()) nicknames.remove(card.assetId) else nicknames[card.assetId] = it.trim() }
        recommended?.let {
            if (it) {
                excluded -= card.assetId
            } else {
                excluded += card.assetId
                if (defaultCard == card.assetId) defaultCard = null
            }
        }
        isDefault?.let { if (it) defaultCard = card.assetId else if (defaultCard == card.assetId) defaultCard = null }
        return ApiResult.Success(card.toInfo())
    }

    /** 카드 전체를 한 번씩 보내야 한다 */
    suspend fun saveCardOrder(userCardIds: List<Long>): ApiResult<Unit> {
        delay(300)
        val current = linkedCards()
        if (userCardIds.size != current.size || userCardIds.toSet() != current.map { it.userCardId() }.toSet()) {
            return ApiResult.Failure(ApiError.InvalidRequest("ORDER_INCOMPLETE"))
        }
        order = userCardIds.map { id -> current.first { it.userCardId() == id }.assetId }
        return ApiResult.Success(Unit)
    }

    suspend fun unregisterCard(userCardId: Long): ApiResult<Unit> {
        delay(500)
        val card = linkedCard(userCardId) ?: return ApiResult.Failure(ApiError.NotFound("RESOURCE_NOT_FOUND"))
        if (card.assetId == "C3") return ApiResult.Failure(ApiError.Conflict("CARD_HAS_OPEN_BILL"))
        linked -= card.assetId
        if (defaultCard == card.assetId) defaultCard = null
        nicknames.remove(card.assetId)
        excluded -= card.assetId
        return ApiResult.Success(Unit)
    }

    private fun verificationFailed(reason: String): ApiResult<Nothing> =
        ApiResult.Failure(ApiError.InvalidRequest("CARD_VERIFICATION_FAILED", buildJsonObject { put("reason", reason) }))

    private companion object {
        const val NUMBER_PREFIX = "941000000000"
    }
}

/** debug 전용 가짜 연결 저장소 — 실제 흐름(카드 확인 → 본인 인증 → 등록 · 계좌 연결)을 그대로 흉내 */
@Singleton
class FakeLinkRepository @Inject constructor(private val bank: FakeBank) : LinkRepository {
    override suspend fun lookupCard(cardNumber: String, expiryMonth: String, expiryYear: String) = bank.lookup(cardNumber, expiryMonth, expiryYear)
    override suspend fun registerCard(verificationToken: String, nickname: String?, stepUpToken: String) = bank.register(verificationToken)
    override suspend fun accountCandidates() = withFault { bank.candidates() }
    override suspend fun linkAccounts(candidateIds: List<String>, idempotencyKey: String, stepUpToken: String) = bank.linkAccounts(candidateIds, idempotencyKey)
    override suspend fun linkedOverview(refresh: Boolean) = withFault(empty = { ApiResult.Success(LinkedOverview(emptyList(), emptyList(), null)) }) { bank.overview() }
    override suspend fun accounts() = withFault(empty = { ApiResult.Success(emptyList()) }) { bank.linkedAccountList() }
    override suspend fun updateAccount(accountId: Long, alias: String?, makePrimary: Boolean) = withFault { bank.updateAccount(accountId, alias, makePrimary) }
    override suspend fun cardDetail(userCardId: Long) = withFault { bank.cardDetail(userCardId) }
    override suspend fun updateCard(userCardId: Long, nickname: String?, recommended: Boolean?, isDefault: Boolean?) = withFault { bank.updateCard(userCardId, nickname, recommended, isDefault) }
    override suspend fun saveCardOrder(userCardIds: List<Long>) = withFault { bank.saveCardOrder(userCardIds) }
    override suspend fun unregisterCard(userCardId: Long, stepUpToken: String) = withFault { bank.unregisterCard(userCardId) }
}

/** debug 전용 가짜 월급 · 고정 지출 — 프로토타입 B-06 숫자(월급 10일 2,800,000 · 월세 · 통신비 · 구독료 + 신한카드 대금) */
@Singleton
class FakeCashflowRepository @Inject constructor() : CashflowRepository {
    var lastPlan: CashflowPlan? = null
        private set

    /**
     * 디버그 메뉴 '모든 기능 상태'를 따른다. 데이터 없음 = 새 사용자(후보 없음 · 생활비 기록 없음),
     * 데이터 부족 = 생활비 기록만 없음(실제 저장소도 422면 제안만 비우고 화면은 연다)
     */
    override suspend fun draft(): ApiResult<CashflowDraft> {
        if (DevSession.faultScenario == FaultScenario.DATA_INSUFFICIENT) {
            delay(300)
            return ApiResult.Success(sampleDraft(dailyLivingCost = null))
        }
        return withFault(empty = { ApiResult.Success(CashflowDraft(null, emptyList(), emptyList(), dailyLivingCost = null)) }) {
            delay(600)
            ApiResult.Success(sampleDraft(dailyLivingCost = 6_000))
        }
    }

    /** 서버는 결제 기록의 반복 지출만 후보로 만들고 월급은 만들지 않는다(BE -349 !200) — 월급은 화면에서 직접 넣는다 */
    private fun sampleDraft(dailyLivingCost: Long?) = CashflowDraft(
        income = null,
        expenses = listOf(
            CashflowCandidate(302, false, "월세", 250_000, 31, "최근 6개월 중 6달 · 마지막 10월 31일"),   // 매월 말일(서버 기준일 31) — B-06 '월세 · 말일'
            CashflowCandidate(303, false, "통신비", 35_000, 27, "최근 6개월 중 4달 · 마지막 10월 27일"),
            CashflowCandidate(304, false, "구독료", 15_000, 1, "최근 6개월 중 3달 · 마지막 11월 1일"),
        ),
        bills = listOf(BillLine("신한카드 대금", 150_000, 20)),
        dailyLivingCost = dailyLivingCost,
        incomeAccounts = listOf(IncomeAccount(1, "월급 통장 ··1234", isPrimary = true), IncomeAccount(2, "생활비 통장 ··5678", isPrimary = false)),
    )

    /** 같은 키는 처음 만든 번호 — 서버 Idempotency-Key 규칙(-358)을 흉내 낸다 */
    private val createdIncomes = mutableMapOf<String, Long>()

    override suspend fun createIncome(body: CashflowCreateRequest, idempotencyKey: String): ApiResult<Long> {
        delay(400)
        return ApiResult.Success(createdIncomes.getOrPut(idempotencyKey) { 900L + createdIncomes.size })
    }

    override suspend fun confirm(plan: CashflowPlan): ApiResult<Unit> {
        delay(800)
        lastPlan = plan
        // 홈 '월급 등록 전'에서 월급을 넣고 오면 가짜 홈도 월급이 있는 모양으로(S15P31E103-429)
        val incomeSet = plan.createdIncomeId != null || (plan.incomeId != null && plan.decisions.any { it.id == plan.incomeId && it.include })
        if (incomeSet && DevSession.homeScenario == HomeScenario.BEFORE_INCOME) DevSession.homeScenario = HomeScenario.NORMAL
        return ApiResult.Success(Unit)
    }
}
