package com.yamu.app.pos

import android.content.Context
import com.yamu.app.BuildConfig
import com.yamu.app.core.network.ApiEnvelope
import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.network.YamuHeaders
import com.yamu.app.core.network.apiCall
import com.yamu.app.core.network.map
import com.yamu.app.feature.link.data.lastDigits
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
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

// 모의 POS 단말(가맹점 쪽) — 단말 고르기 · #27 결제 요청 · #63 결과. POS API는 단말 키(X-POS-Key)로 부른다(10 문서 6-5)
// 고객 앱 결제(feature/payment)의 가맹점 모델은 쓰지 않는다. POS가 보는 가맹점은 단말과 그 브랜드의 번호뿐이다

const val POS_KEY_HEADER = "X-POS-Key"

/**
 * BE `yamu.pos.terminals`(be application.yml)에 있는 단말. BE 쪽이 바뀌면 여기도 같이 고친다.
 * 결제 요청의 merchantId는 [brandCode]가 같은 가맹점이어야 한다(아니면 404 MERCHANT_NOT_FOUND).
 * 가맹점 번호는 시드마다 달라서 적어 두지 않고, 고를 때 업종별 브랜드 목록에서 [brandCode]로 찾는다
 */
data class PosTerminal(
    val posCode: String,
    val brandCode: String,
    val merchantCategoryCode: String,
    val name: String,
    val place: String,
)

val POS_TERMINALS = listOf(
    PosTerminal("POS-YAMU-CAFE", "YAMU_TEST_CAFE", "COFFEE_SHOP", "야무 테스트 카페", "카페 · 일반 매장"),
    PosTerminal("POS-STARBUCKS-GENERAL", "STARBUCKS", "COFFEE_SHOP", "스타벅스", "카페 · 일반 매장"),
    PosTerminal("POS-STARBUCKS-DEPARTMENT_STORE", "STARBUCKS", "COFFEE_SHOP", "스타벅스 백화점점", "카페 · 백화점 입점"),
    PosTerminal("POS-GS25-GENERAL", "GS25", "CONVENIENCE_STORE", "GS25", "편의점 · 일반 매장"),
)

/** 이 단말이 결제를 거는 가맹점 — 고른 단말의 posCode와 서버에서 찾은 가맹점 번호 */
data class PosMerchant(val merchantId: Long, val posCode: String, val name: String)

/** `GET /api/merchants?merchantCategoryCode=` 한 줄(인증 없음) */
@Serializable data class PosMerchantDto(
    val merchantId: Long,
    val brandCode: String? = null,
    val merchantName: String? = null,
    val merchantCategoryCode: String? = null,
)

/** 결제 요청 본문. plan을 보내지 않으면 일시불이다(null을 보내면 400이라 칸을 두지 않는다) */
@Serializable data class PosPaymentRequest(val barcodeToken: String, val merchantId: Long, val posCode: String, val amount: Long, val orderNo: String)

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
    val status: String,                       // REQUESTED · PROCESSING · APPROVED · DECLINED · CANCELED · EXPIRED
    val approvalNo: String? = null,
    val approvedAt: String? = null,
    val amount: Long = 0,
    val chargedAmount: Long? = null,
    val instantDiscount: Long? = null,
    val card: PosCardDto? = null,             // 승인 전에는 null
    val failure: PosFailureDto? = null,
    val retryable: Boolean? = null,           // DECLINED일 때만 값. false면 마감 전이어도 최종
)

interface PosApi {
    @GET("merchants")
    suspend fun merchants(@Query("merchantCategoryCode") merchantCategoryCode: String): Response<ApiEnvelope<List<PosMerchantDto>>>

    @POST("pos/payment-requests")
    suspend fun request(
        @Header(POS_KEY_HEADER) posKey: String,
        @Header(YamuHeaders.IDEMPOTENCY_KEY) idempotencyKey: String,
        @Body body: PosPaymentRequest,
    ): Response<ApiEnvelope<PosRequestResponse>>

    /** 결제를 만든 단말의 posCode가 있어야 한다(없으면 400, 다르면 404) */
    @GET("pos/payment-requests/{id}")
    suspend fun result(
        @Header(POS_KEY_HEADER) posKey: String,
        @Path("id") paymentId: Long,
        @Query("posCode") posCode: String,
    ): Response<ApiEnvelope<PosResultDto>>
}

/**
 * 결제 요청 한 번. 다시 보낼 때는 키와 본문(주문번호까지)을 그대로 보낸다.
 * BE는 키와 본문 지문으로 같은 요청을 알아보고 처음 paymentId를 돌려준다. 본문이 하나라도 다르면 409 IDEMPOTENCY_KEY_REUSED
 */
data class PosAttempt(val idempotencyKey: String, val body: PosPaymentRequest)

private val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
private val ORDER_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

/** POS 주문번호(서울 시각 + 난수 세 자리, 웹 POS와 같은 모양). 시도를 만들 때 한 번만 정한다 */
fun newPosOrderNo(now: ZonedDateTime = ZonedDateTime.now(SEOUL), random: Random = Random.Default): String =
    "POS-" + now.withZoneSameInstant(SEOUL).format(ORDER_TIME) + "-" + "%03d".format(random.nextInt(1000))

/** 화면이 볼 결과 상태. [RETRYING]은 거절됐지만 고객이 마감 전에 다른 카드로 다시 승인할 수 있는 상태다 */
enum class PosStatus { WAITING, PROCESSING, RETRYING, APPROVED, DECLINED, EXPIRED, CANCELED }

/** 더 기다려도 바뀌지 않는 상태 — 여기서 결과 조회를 멈추고 영수증 · 안내를 띄운다(웹 POS WaitingStep과 같은 규칙) */
val PosStatus.isFinal: Boolean
    get() = this == PosStatus.APPROVED || this == PosStatus.DECLINED || this == PosStatus.EXPIRED || this == PosStatus.CANCELED

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
        // BE는 DECLINED에 retryable을 늘 채운다. 비어 오면 기다리는 쪽으로 둔다(마감이 지나면 서버가 EXPIRED로 바꾼다)
        "DECLINED" -> if (retryable == false) PosStatus.DECLINED else PosStatus.RETRYING
        "EXPIRED" -> PosStatus.EXPIRED
        "CANCELED" -> PosStatus.CANCELED
        "PROCESSING" -> PosStatus.PROCESSING
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
    /** 단말 브랜드의 가맹점. 서버에 그 브랜드가 없으면 NotFound(MERCHANT_NOT_FOUND) */
    suspend fun merchantFor(terminal: PosTerminal): ApiResult<PosMerchant>
    /** 결제 요청(#27) → paymentId */
    suspend fun request(attempt: PosAttempt): ApiResult<Long>
    /** 결제 결과(#63) — 결제를 만든 단말의 [posCode]로만 볼 수 있다 */
    suspend fun result(paymentId: Long, posCode: String): ApiResult<PosResult>
}

@Singleton
class PosRepositoryImpl internal constructor(private val api: PosApi, private val key: String) : PosRepository {
    @Inject constructor(api: PosApi) : this(api, BuildConfig.POS_KEY)

    override val hasKey: Boolean get() = key.isNotBlank()

    override suspend fun merchantFor(terminal: PosTerminal): ApiResult<PosMerchant> =
        when (val r = apiCall { api.merchants(terminal.merchantCategoryCode) }) {
            is ApiResult.Success -> r.data.firstOrNull { it.brandCode == terminal.brandCode }
                ?.let { ApiResult.Success(PosMerchant(it.merchantId, terminal.posCode, terminal.name)) }
                ?: ApiResult.Failure(ApiError.NotFound("MERCHANT_NOT_FOUND"))
            is ApiResult.Failure -> r
        }

    override suspend fun request(attempt: PosAttempt): ApiResult<Long> =
        apiCall { api.request(key, attempt.idempotencyKey, attempt.body) }.map { it.paymentId }

    override suspend fun result(paymentId: Long, posCode: String): ApiResult<PosResult> =
        apiCall { api.result(key, paymentId, posCode) }.map { it.toResult() }
}

/** 이 단말의 가맹점 — 한 번 고르면 다음에도. 예전 버전이 남긴 값(posCode 없음)은 버리고 다시 고르게 한다 */
@Singleton
class PosSettings @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("yamu_pos", Context.MODE_PRIVATE)
    var merchant: PosMerchant?
        get() {
            val id = prefs.getLong("merchantId", -1L).takeIf { it > 0 } ?: return null
            val posCode = prefs.getString("posCode", null)?.takeIf { code -> POS_TERMINALS.any { it.posCode == code } } ?: return null
            return PosMerchant(id, posCode, prefs.getString("merchantName", null) ?: return null)
        }
        set(value) {
            prefs.edit().apply {
                clear()
                if (value != null) putLong("merchantId", value.merchantId).putString("posCode", value.posCode).putString("merchantName", value.name)
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
