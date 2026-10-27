package com.yamu.app.feature.link.data

import com.yamu.app.core.network.ApiEnvelope
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.network.YamuHeaders
import com.yamu.app.core.network.apiCall
import com.yamu.app.core.network.map
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
import retrofit2.http.Path
import retrofit2.http.Query
import javax.inject.Inject
import javax.inject.Singleton

/** 계좌 · 카드 연결 — 보완안 추가 1~5 + #31(세션으로 옮김) · #83 · #7. 09 문서 5-2 */
interface LinkApi {
    @GET("institutions")
    suspend fun institutions(@Query("type") type: String?): Response<ApiEnvelope<InstitutionsResponse>>

    @POST("link-sessions")
    suspend fun createSession(@Body body: CreateLinkSessionRequest): Response<ApiEnvelope<LinkSessionDto>>

    @GET("link-sessions/{id}")
    suspend fun session(@Path("id") id: String): Response<ApiEnvelope<LinkSessionDto>>

    @POST("link-sessions/{id}/one-won-auth")
    suspend fun sendOneWon(@Path("id") id: String): Response<ApiEnvelope<OneWonResponse>>

    @POST("link-sessions/{id}/verify")
    suspend fun verify(
        @Path("id") id: String,
        @Header(YamuHeaders.STEP_UP_TOKEN) stepUpToken: String,
        @Body body: LinkVerifyRequest,
    ): Response<ApiEnvelope<LinkVerifyResponse>>

    @GET("link-sessions/{id}/assets")
    suspend fun assets(@Path("id") id: String): Response<ApiEnvelope<LinkAssetsResponse>>

    @POST("link-sessions/{id}/complete")
    suspend fun complete(
        @Path("id") id: String,
        @Header(YamuHeaders.IDEMPOTENCY_KEY) idempotencyKey: String,
        @Header(YamuHeaders.STEP_UP_TOKEN) stepUpToken: String?,     // 카드 추가(CARD_REGISTER)만
        @Body body: LinkCompleteRequest,
    ): Response<ApiEnvelope<LinkCompleteResponse>>

    /** #83 — refresh=true면 금융망 잔액을 다시 읽음 */
    @GET("accounts")
    suspend fun accounts(@Query("refresh") refresh: Boolean?): Response<ApiEnvelope<AccountsResponse>>

    /** #7 */
    @GET("cards")
    suspend fun cards(): Response<ApiEnvelope<CardsResponse>>
}

interface LinkRepository {
    suspend fun institutions(type: InstitutionType? = null): ApiResult<List<Institution>>
    suspend fun startSession(institutionIds: List<String>, purpose: String): ApiResult<LinkSession>
    suspend fun session(id: String): ApiResult<LinkSession>

    /** 내 명의 계좌로 1원 보내기 — 받는 쪽 숫자 자리수 · 앞 글자('YAMU') */
    suspend fun sendOneWon(id: String): ApiResult<OneWonSent>

    /** 1원 숫자 확인 + 약관 서명(지문 한 번) — 본인 인증 토큰(ACCOUNT_LINK) 필요 */
    suspend fun verify(id: String, stepUpToken: String, authCode: String?, agreements: List<Terms>): ApiResult<Unit>

    suspend fun assets(id: String): ApiResult<LinkAssets>

    /** 연결 확정(일괄). [idempotencyKey]는 다시 시도할 때도 같은 값 */
    suspend fun complete(
        id: String,
        accountAssetIds: List<String>,
        cardAssetIds: List<String>,
        primaryAccountAssetId: String?,
        idempotencyKey: String,
        stepUpToken: String? = null,
    ): ApiResult<LinkCompletion>

    /** 연결된 계좌(잔액 — 금융망) · 카드 */
    suspend fun linkedOverview(refresh: Boolean = false): ApiResult<LinkedOverview>
}

@Singleton
class LinkRepositoryImpl @Inject constructor(private val api: LinkApi) : LinkRepository {

    override suspend fun institutions(type: InstitutionType?): ApiResult<List<Institution>> =
        apiCall { api.institutions(type?.name) }.map { r -> r.institutions.map { it.toInstitution() } }

    override suspend fun startSession(institutionIds: List<String>, purpose: String): ApiResult<LinkSession> =
        apiCall { api.createSession(CreateLinkSessionRequest(institutionIds, purpose)) }.map { it.toSession() }

    override suspend fun session(id: String): ApiResult<LinkSession> = apiCall { api.session(id) }.map { it.toSession() }

    override suspend fun sendOneWon(id: String): ApiResult<OneWonSent> =
        apiCall { api.sendOneWon(id) }.map { OneWonSent(it.depositorNamePrefix, it.codeLength, it.authExpiresAt) }

    override suspend fun verify(id: String, stepUpToken: String, authCode: String?, agreements: List<Terms>): ApiResult<Unit> =
        apiCall {
            api.verify(id, stepUpToken, LinkVerifyRequest(authCode, agreements.map { AgreementDto(it.code, it.version) }))
        }.map { }

    override suspend fun assets(id: String): ApiResult<LinkAssets> = apiCall { api.assets(id) }.map { it.toAssets() }

    override suspend fun complete(
        id: String,
        accountAssetIds: List<String>,
        cardAssetIds: List<String>,
        primaryAccountAssetId: String?,
        idempotencyKey: String,
        stepUpToken: String?,
    ): ApiResult<LinkCompletion> =
        apiCall {
            api.complete(id, idempotencyKey, stepUpToken, LinkCompleteRequest(accountAssetIds, cardAssetIds, primaryAccountAssetId))
        }.map { r -> LinkCompletion(r.accounts.map { it.accountId }, r.cards.map { it.userCardId }) }

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
