package com.yamu.app.pos

import com.yamu.app.BuildConfig
import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.data.FakeMerchants
import com.yamu.app.feature.payment.data.Merchant
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Provider
import javax.inject.Singleton
import javax.inject.Inject

/**
 * debug 전용 가짜 POS 서버 — 결제 요청 → 5초 뒤 '고객이 확정함'(승인). 고객 앱과 따로 돌아서 진짜 결제는 실제 서버로만.
 * 시험값(번호로 넣기): EXPIRED가 들어가면 만료 · USED는 이미 쓴 코드 · DECLINE은 거절 · REJECT는 고객 거절
 */
@Singleton
class FakePosRepository @Inject constructor() : PosRepository {
    private class Pending(val token: String, val amount: Long, val at: Long)

    private val requests = mutableMapOf<Long, Pending>()
    private var nextId = 5001L

    override val hasKey: Boolean = true

    override suspend fun merchants(): ApiResult<List<Merchant>> {
        delay(300)
        return ApiResult.Success(FakeMerchants.all.filter { !it.isOnline })
    }

    override suspend fun request(barcodeToken: String, merchantId: Long, amount: Long, orderNo: String, idempotencyKey: String): ApiResult<Long> {
        delay(600)
        return when {
            "EXPIRED" in barcodeToken -> ApiResult.Failure(ApiError.Unknown("BARCODE_EXPIRED", 410))
            "USED" in barcodeToken -> ApiResult.Failure(ApiError.Conflict("BARCODE_ALREADY_USED"))
            else -> {
                val id = nextId++
                requests[id] = Pending(barcodeToken, amount, System.currentTimeMillis())
                ApiResult.Success(id)
            }
        }
    }

    override suspend fun result(paymentId: Long): ApiResult<PosResult> {
        delay(200)
        val p = requests[paymentId] ?: return ApiResult.Failure(ApiError.NotFound("RESOURCE_NOT_FOUND"))
        val waiting = System.currentTimeMillis() - p.at < 5_000
        val result = when {
            waiting -> PosResult(PosStatus.WAITING, null, p.amount, null, null, null, null, null, null)
            "DECLINE" in p.token -> PosResult(PosStatus.DECLINED, null, p.amount, null, null, null, null, null, "고객 카드가 승인되지 않았어요(잔액 부족)")
            "REJECT" in p.token -> PosResult(PosStatus.ENDED, null, p.amount, null, null, null, null, null, null)
            else -> PosResult(
                status = PosStatus.APPROVED,
                approvalNo = "Y" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + "%03d".format(paymentId % 1000),
                amount = p.amount,
                chargedAmount = p.amount,
                instantDiscount = null,
                cardIssuer = "신한카드",
                cardLast4 = "1021",
                installmentMonths = null,
                failureMessage = null,
            )
        }
        return ApiResult.Success(result)
    }
}

/** 모의 POS debug: USE_FAKE_API면 가짜, 아니면 진짜(release는 src/posRelease) */
@Module
@InstallIn(SingletonComponent::class)
object PosDataModule {
    @Provides
    @Singleton
    fun posRepository(real: Provider<PosRepositoryImpl>, fake: Provider<FakePosRepository>): PosRepository =
        if (BuildConfig.USE_FAKE_API) fake.get() else real.get()
}
