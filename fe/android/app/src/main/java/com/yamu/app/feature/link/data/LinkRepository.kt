package com.yamu.app.feature.link.data

import com.yamu.app.core.network.ApiEnvelope
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.network.YamuHeaders
import com.yamu.app.core.network.apiCall
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
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
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
}

@Module
@InstallIn(SingletonComponent::class)
object LinkApiModule {
    @Provides
    @Singleton
    fun linkApi(retrofit: Retrofit): LinkApi = retrofit.create(LinkApi::class.java)
}
