package com.yamu.app.pos

import android.content.Context
import com.yamu.app.BuildConfig
import com.yamu.app.core.network.ApiEnvelope
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.network.YamuHeaders
import com.yamu.app.core.network.apiCall
import com.yamu.app.core.network.map
import com.yamu.app.feature.link.data.lastDigits
import com.yamu.app.feature.payment.data.Merchant
import com.yamu.app.feature.payment.data.MerchantsResponse
import com.yamu.app.feature.payment.data.toMerchant
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.Serializable
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

// 모의 POS 단말(가맹점 쪽) — #5 가맹점 · #27 결제 요청 · #63 결과. 가맹점 단말 키(X-POS-Key)로만 부른다(10 문서 6-5)

const val POS_KEY_HEADER = "X-POS-Key"

@Serializable data class PosPaymentRequest(val barcodeToken: String, val merchantId: Long, val amount: Long, val orderNo: String)

@Serializable data class PosRequestResponse(val paymentId: Long, val orderNo: String? = null, val status: String = "REQUESTED", val confirmDeadline: String? = null)

@Serializable data class PosCardDto(
    val issuerName: String? = null,
    val cardType: String? = null,
    val maskedNumber: String? = null,
    val plan: String? = null,
    val installmentMonths: Int? = null,
)

@Serializable data class PosFailureDto(val code: String, val message: String? = null)

@Serializable data class PosResultDto(
    val paymentId: Long,
    val orderNo: String? = null,
    val status: String,                       // REQUESTED · PROCESSING · APPROVED · DECLINED · EXPIRED · REJECTED
    val approvalNo: String? = null,
    val approvedAt: String? = null,
    val amount: Long = 0,
    val chargedAmount: Long? = null,
    val instantDiscount: Long? = null,
    val card: PosCardDto? = null,
    val failure: PosFailureDto? = null,
)

interface PosApi {
    @GET("merchants")
    suspend fun merchants(@Header(POS_KEY_HEADER) posKey: String, @Query("channel") channel: String = "OFFLINE", @Query("size") size: Int = 50): Response<ApiEnvelope<MerchantsResponse>>

    @POST("pos/payment-requests")
    suspend fun request(
        @Header(POS_KEY_HEADER) posKey: String,
        @Header(YamuHeaders.IDEMPOTENCY_KEY) idempotencyKey: String,
        @Body body: PosPaymentRequest,
    ): Response<ApiEnvelope<PosRequestResponse>>

    @GET("pos/payment-requests/{id}")
    suspend fun result(@Header(POS_KEY_HEADER) posKey: String, @Path("id") paymentId: Long): Response<ApiEnvelope<PosResultDto>>
}

enum class PosStatus { WAITING, APPROVED, DECLINED, ENDED }

/** 영수증에 찍을 것 — 고객 이름 · 추천은 받지도 보여 주지도 않는다 */
data class PosResult(
    val status: PosStatus,
    val approvalNo: String?,
    val amount: Long,
    val chargedAmount: Long?,
    val instantDiscount: Long?,
    val cardIssuer: String?,
    val cardLast4: String?,
    val installmentMonths: Int?,
    val failureMessage: String?,
)

fun PosResultDto.toResult() = PosResult(
    status = when (status.uppercase()) {
        "APPROVED" -> PosStatus.APPROVED
        "DECLINED" -> PosStatus.DECLINED
        "EXPIRED", "REJECTED", "CANCELED" -> PosStatus.ENDED
        else -> PosStatus.WAITING
    },
    approvalNo = approvalNo,
    amount = amount,
    chargedAmount = chargedAmount,
    instantDiscount = instantDiscount?.takeIf { it > 0 },
    cardIssuer = card?.issuerName,
    cardLast4 = card?.maskedNumber?.lastDigits(),
    installmentMonths = card?.installmentMonths?.takeIf { card.plan.equals("INSTALLMENT", ignoreCase = true) },
    failureMessage = failure?.message,
)

interface PosRepository {
    /** 단말 키가 있나(없으면 첫 화면에 안내) */
    val hasKey: Boolean
    suspend fun merchants(): ApiResult<List<Merchant>>
    suspend fun request(barcodeToken: String, merchantId: Long, amount: Long, orderNo: String, idempotencyKey: String): ApiResult<Long>
    suspend fun result(paymentId: Long): ApiResult<PosResult>
}

@Singleton
class PosRepositoryImpl @Inject constructor(private val api: PosApi) : PosRepository {
    private val key = BuildConfig.POS_KEY
    override val hasKey: Boolean get() = key.isNotBlank()

    override suspend fun merchants(): ApiResult<List<Merchant>> =
        apiCall { api.merchants(key) }.map { r -> r.items.map { it.toMerchant() }.filter { !it.isOnline } }

    override suspend fun request(barcodeToken: String, merchantId: Long, amount: Long, orderNo: String, idempotencyKey: String): ApiResult<Long> =
        apiCall { api.request(key, idempotencyKey, PosPaymentRequest(barcodeToken, merchantId, amount, orderNo)) }.map { it.paymentId }

    override suspend fun result(paymentId: Long): ApiResult<PosResult> = apiCall { api.result(key, paymentId) }.map { it.toResult() }
}

/** 이 단말의 가맹점 — 한 번 고르면 다음에도 */
@Singleton
class PosSettings @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("yamu_pos", Context.MODE_PRIVATE)
    var merchant: Merchant?
        get() {
            val id = prefs.getLong("merchantId", -1L).takeIf { it > 0 } ?: return null
            return Merchant(id, prefs.getString("merchantName", null) ?: return null, prefs.getString("category", null), isOnline = false, posCode = null)
        }
        set(value) {
            prefs.edit().apply {
                if (value == null) clear() else putLong("merchantId", value.id).putString("merchantName", value.name).putString("category", value.categoryName)
            }.apply()
        }
}

@Module
@InstallIn(SingletonComponent::class)
object PosApiModule {
    @Provides
    @Singleton
    fun posApi(retrofit: Retrofit): PosApi = retrofit.create(PosApi::class.java)
}
