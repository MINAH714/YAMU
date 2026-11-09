package com.yamu.app.pos

import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiFeature
import com.yamu.app.core.network.ApiResult
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * debug 전용 가짜 POS 서버 — 결제 요청 → 5초 뒤 '고객이 확정함'(승인). 고객 앱과 따로 돌아서 진짜 결제는 실제 서버로만.
 * 서버처럼 같은 키 · 같은 본문이면 처음 결제 번호를, 같은 키에 다른 본문이면 409를 돌려주고, 한 번 쓴 코드는 다시 받지 않는다.
 * 시험값(번호로 넣기): EXPIRED가 들어가면 만료 · USED는 이미 쓴 코드 · LOST는 첫 응답이 끊김(서버엔 접수, 다시 보내면 같은 결제) ·
 * DECLINEFINAL은 최종 거절 · DECLINE은 거절 뒤 고객이 다른 카드로 승인 · REJECT는 고객이 승인하지 않아 만료
 */
@Singleton
class FakePosRepository @Inject constructor() : PosRepository {
    private class Pending(val attempt: PosAttempt, val at: Long)

    private val requests = mutableMapOf<Long, Pending>()
    private val byKey = mutableMapOf<String, Long>()
    private var nextId = 5001L

    override val hasKey: Boolean = true

    override suspend fun merchantFor(terminal: PosTerminal): ApiResult<PosMerchant> {
        delay(300)
        return ApiResult.Success(PosMerchant(101L + POS_TERMINALS.indexOf(terminal), terminal.posCode, terminal.name))
    }

    override suspend fun request(attempt: PosAttempt): ApiResult<Long> {
        delay(600)
        byKey[attempt.idempotencyKey]?.let { id ->
            return if (requests[id]?.attempt == attempt) ApiResult.Success(id) else ApiResult.Failure(ApiError.Conflict("IDEMPOTENCY_KEY_REUSED"))
        }
        val token = attempt.body.barcodeToken
        return when {
            "EXPIRED" in token -> ApiResult.Failure(ApiError.Unknown("BARCODE_EXPIRED", 410))
            "USED" in token || requests.values.any { it.attempt.body.barcodeToken == token } -> ApiResult.Failure(ApiError.Conflict("BARCODE_ALREADY_USED"))
            else -> {
                val id = nextId++
                requests[id] = Pending(attempt, System.currentTimeMillis())
                byKey[attempt.idempotencyKey] = id
                if ("LOST" in token) ApiResult.Failure(ApiError.Network) else ApiResult.Success(id)
            }
        }
    }

    override suspend fun result(paymentId: Long, posCode: String): ApiResult<PosResult> {
        delay(200)
        val p = requests[paymentId]?.takeIf { it.attempt.body.posCode == posCode }
            ?: return ApiResult.Failure(ApiError.NotFound("RESOURCE_NOT_FOUND"))
        val elapsed = System.currentTimeMillis() - p.at
        val token = p.attempt.body.barcodeToken
        val amount = p.attempt.body.amount
        val status = when {
            elapsed < 5_000 -> PosStatus.WAITING
            "DECLINEFINAL" in token -> PosStatus.DECLINED
            "DECLINE" in token && elapsed < 10_000 -> PosStatus.RETRYING
            "REJECT" in token -> if (elapsed < 15_000) PosStatus.WAITING else PosStatus.EXPIRED
            else -> PosStatus.APPROVED
        }
        val result = if (status == PosStatus.APPROVED) {
            PosResult(
                status = status,
                approvalNo = "Y" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + "%03d".format(paymentId % 1000),
                amount = amount,
                chargedAmount = amount,
                instantDiscount = null,
                cardIssuer = "신한카드",
                cardLast4 = "1021",
                installmentMonths = null,
                failureMessage = null,
            )
        } else {
            val declined = status == PosStatus.DECLINED || status == PosStatus.RETRYING
            PosResult(status, null, amount, null, null, null, null, null, if (declined) "연결 계좌 잔액이 부족해요" else null)
        }
        return ApiResult.Success(result)
    }
}

/** 모의 POS debug: yamu.realApi에 pos가 있으면 진짜, 아니면 가짜(release는 src/posRelease) */
@Module
@InstallIn(SingletonComponent::class)
object PosDataModule {
    @Provides
    @Singleton
    fun posRepository(real: Provider<PosRepositoryImpl>, fake: Provider<FakePosRepository>): PosRepository =
        if (ApiFeature.POS.isReal) real.get() else fake.get()
}
