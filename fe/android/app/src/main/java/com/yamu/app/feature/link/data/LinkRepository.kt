package com.yamu.app.feature.link.data

import com.yamu.app.core.network.ApiEnvelope
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.network.YamuHeaders
import com.yamu.app.core.network.apiCall
import com.yamu.app.core.network.apiCallNoData
import com.yamu.app.core.network.map
import com.yamu.app.feature.auth.data.AccountCandidate
import com.yamu.app.feature.auth.data.AccountCandidatesResponse
import com.yamu.app.feature.auth.data.toCandidate
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import javax.inject.Inject
import javax.inject.Singleton

/** 카드 확인 · 등록(BE CardController) · 로그인 후 계좌 추가(BE AccountLinkController) · 연결된 계좌 · 카드 목록 */
interface LinkApi {
    @POST("cards/lookup")
    suspend fun lookupCard(@Body body: CardLookupRequest): Response<ApiEnvelope<CardLookupResponse>>

    @POST("cards")
    suspend fun registerCard(
        @Header(YamuHeaders.STEP_UP_TOKEN) stepUpToken: String,   // CARD_REGISTER
        @Body body: CardRegisterRequest,
    ): Response<ApiEnvelope<CardRegisterResponse>>

    @GET("accounts/candidates")
    suspend fun accountCandidates(): Response<ApiEnvelope<AccountCandidatesResponse>>

    @POST("accounts")
    suspend fun linkAccounts(
        @Header(YamuHeaders.IDEMPOTENCY_KEY) idempotencyKey: String,
        @Header(YamuHeaders.STEP_UP_TOKEN) stepUpToken: String,   // ACCOUNT_LINK
        @Body body: MemberAccountLinkRequest,
    ): Response<ApiEnvelope<AccountLinkResponse>>

    /** #83 — refresh=true면 금융망 잔액을 다시 읽음 */
    @GET("accounts")
    suspend fun accounts(@Query("refresh") refresh: Boolean?): Response<ApiEnvelope<AccountsResponse>>

    /** #7 */
    @GET("cards")
    suspend fun cards(): Response<ApiEnvelope<CardsResponse>>

    @PATCH("accounts/{id}")
    suspend fun updateAccount(@Path("id") accountId: Long, @Body body: AccountUpdateRequest): Response<ApiEnvelope<AccountDto>>

    @GET("cards/{id}")
    suspend fun card(@Path("id") userCardId: Long): Response<ApiEnvelope<CardDetailDto>>

    @PATCH("cards/{id}")
    suspend fun updateCard(@Path("id") userCardId: Long, @Body body: CardUpdateRequest): Response<ApiEnvelope<CardDetailDto>>

    @PUT("cards/order")
    suspend fun saveCardOrder(@Body body: CardOrderRequest): Response<ApiEnvelope<JsonElement>>

    /** 204 — 행은 남고 UNREGISTERED로 바뀐다 */
    @DELETE("cards/{id}")
    suspend fun unregisterCard(
        @Header(YamuHeaders.STEP_UP_TOKEN) stepUpToken: String,   // CARD_DELETE
        @Path("id") userCardId: Long,
    ): Response<ApiEnvelope<JsonElement>>
}

interface LinkRepository {
    /** 카드번호 · 유효기간으로 내 명의 카드를 찾는다(1분 5번). [expiryMonth] MM · [expiryYear] YY. 번호는 넘기기만 하고 어디에도 남기지 않는다 */
    suspend fun lookupCard(cardNumber: String, expiryMonth: String, expiryYear: String): ApiResult<CardLookup>

    /** 확인한 카드 등록. [nickname]이 null이면 별칭 없음 · 본인 인증 토큰(CARD_REGISTER) 필요 */
    suspend fun registerCard(verificationToken: String, nickname: String?, stepUpToken: String): ApiResult<RegisteredCard>

    /** 아직 연결하지 않은 내 명의 계좌 */
    suspend fun accountCandidates(): ApiResult<List<AccountCandidate>>

    /** 고른 계좌 연결. [idempotencyKey]는 다시 시도할 때도 같은 값 · 본인 인증 토큰(ACCOUNT_LINK) 필요 */
    suspend fun linkAccounts(candidateIds: List<String>, idempotencyKey: String, stepUpToken: String): ApiResult<AccountLinkResult>

    /** 연결된 계좌(잔액 — 금융망) · 카드 */
    suspend fun linkedOverview(refresh: Boolean = false): ApiResult<LinkedOverview>

    /** 연결된 계좌만(잔액 — 금융망). 카드 목록이 실패해도 계좌 화면은 열려야 한다 */
    suspend fun accounts(): ApiResult<List<LinkedAccount>>

    /** 등록된 카드만(금융망을 부르지 않는다). 결제 요청 화면이 카드사 · 상품명을 붙일 때 쓴다 */
    suspend fun cards(): ApiResult<List<LinkedCard>> = linkedOverview().map { it.cards }

    /** 넘긴 값만 바꾸고 바뀐 계좌를 돌려준다. [alias] ""은 별칭 지우기. [makePrimary]면 이 계좌가 기준 계좌가 되고 전에 있던 기준 계좌는 풀린다 */
    suspend fun updateAccount(accountId: Long, alias: String? = null, makePrimary: Boolean = false): ApiResult<LinkedAccount>

    /** 내 카드 한 장. 없거나 해제한 카드 · 남의 카드는 404 */
    suspend fun cardDetail(userCardId: Long): ApiResult<CardInfo>

    /** 넘긴 값만 바꾸고 바뀐 카드를 돌려준다. [nickname] ""은 별칭 지우기. 기본 카드를 추천에서 빼면 기본 지정도 풀린다 */
    suspend fun updateCard(userCardId: Long, nickname: String? = null, recommended: Boolean? = null, isDefault: Boolean? = null): ApiResult<CardInfo>

    /** 등록된 카드 전체의 순서. 그 사이 카드가 바뀌었으면 ORDER_INCOMPLETE */
    suspend fun saveCardOrder(userCardIds: List<Long>): ApiResult<Unit>

    /** 카드 해제 · 본인 인증 토큰(CARD_DELETE) 필요 */
    suspend fun unregisterCard(userCardId: Long, stepUpToken: String): ApiResult<Unit>
}

@Singleton
class LinkRepositoryImpl @Inject constructor(private val api: LinkApi) : LinkRepository {

    override suspend fun lookupCard(cardNumber: String, expiryMonth: String, expiryYear: String): ApiResult<CardLookup> =
        apiCall { api.lookupCard(CardLookupRequest(cardNumber, expiryMonth, expiryYear)) }.map { it.toLookup() }

    override suspend fun registerCard(verificationToken: String, nickname: String?, stepUpToken: String): ApiResult<RegisteredCard> =
        apiCall { api.registerCard(stepUpToken, CardRegisterRequest(verificationToken, nickname)) }.map { it.toRegistered() }

    override suspend fun accountCandidates(): ApiResult<List<AccountCandidate>> =
        apiCall { api.accountCandidates() }.map { r -> r.accounts.map { it.toCandidate() } }

    override suspend fun linkAccounts(candidateIds: List<String>, idempotencyKey: String, stepUpToken: String): ApiResult<AccountLinkResult> =
        apiCall { api.linkAccounts(idempotencyKey, stepUpToken, MemberAccountLinkRequest(candidateIds)) }.map { it.toResult() }

    override suspend fun linkedOverview(refresh: Boolean): ApiResult<LinkedOverview> = coroutineScope {
        val accounts = async { apiCall { api.accounts(refresh.takeIf { it }) } }
        val cards = async { apiCall { api.cards() } }
        val a = accounts.await()
        val c = cards.await()
        when {
            a is ApiResult.Failure -> a
            c is ApiResult.Failure -> c
            else -> {
                val ad = (a as ApiResult.Success).data
                val cd = (c as ApiResult.Success).data
                ApiResult.Success(LinkedOverview(ad.accounts.map { it.toLinked() }, cd.cards.map { it.toLinked() }, ad.dataSyncedAt ?: cd.dataSyncedAt))
            }
        }
    }

    override suspend fun accounts(): ApiResult<List<LinkedAccount>> = apiCall { api.accounts(null) }.map { r -> r.accounts.map { it.toLinked() } }

    override suspend fun cards(): ApiResult<List<LinkedCard>> = apiCall { api.cards() }.map { r -> r.cards.map { it.toLinked() } }

    override suspend fun updateAccount(accountId: Long, alias: String?, makePrimary: Boolean): ApiResult<LinkedAccount> =
        apiCall { api.updateAccount(accountId, AccountUpdateRequest(alias, true.takeIf { makePrimary })) }.map { it.toLinked() }

    override suspend fun cardDetail(userCardId: Long): ApiResult<CardInfo> = apiCall { api.card(userCardId) }.map { it.toInfo() }

    override suspend fun updateCard(userCardId: Long, nickname: String?, recommended: Boolean?, isDefault: Boolean?): ApiResult<CardInfo> {
        val status = recommended?.let { if (it) "ACTIVE" else "INACTIVE" }
        return apiCall { api.updateCard(userCardId, CardUpdateRequest(nickname, status, isDefault)) }.map { it.toInfo() }
    }

    override suspend fun saveCardOrder(userCardIds: List<Long>): ApiResult<Unit> = apiCallNoData { api.saveCardOrder(CardOrderRequest(userCardIds)) }

    override suspend fun unregisterCard(userCardId: Long, stepUpToken: String): ApiResult<Unit> = apiCallNoData { api.unregisterCard(stepUpToken, userCardId) }
}

@Module
@InstallIn(SingletonComponent::class)
object LinkApiModule {
    @Provides
    @Singleton
    fun linkApi(retrofit: Retrofit): LinkApi = retrofit.create(LinkApi::class.java)
}
