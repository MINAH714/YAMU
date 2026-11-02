package com.yamu.app.data

import com.yamu.app.core.network.ApiResult
import com.yamu.app.feature.my.data.Device
import com.yamu.app.feature.my.data.MyRepository
import com.yamu.app.feature.my.data.MyRepositoryImpl
import com.yamu.app.feature.payment.data.Merchant
import com.yamu.app.feature.payment.data.PaymentReceipt
import com.yamu.app.feature.payment.data.PaymentRepository
import com.yamu.app.feature.payment.data.PaymentRepositoryImpl
import com.yamu.app.feature.payment.data.Recommendation
import javax.inject.Inject

// debug 전용 — yamu.realApi로 켠 기능 중 BE에 아직 없는 API만 가짜로 메운다. release에는 들어가지 않는다.
// BE에 API가 생기면 여기 줄을 지운다. 지울 게 없어지면 클래스째 지우고 DataModule이 Impl을 바로 쓰게 한다.

/** 진짜: 결제 코드 발급 · 상태 · 승인. 가짜: 가맹점 목록 · 결제 전 추천 · 거절 · 결제 상세 */
class RealPaymentWithFakes @Inject constructor(
    private val real: PaymentRepositoryImpl,
    private val fake: FakePaymentRepository,
) : PaymentRepository by real {
    override suspend fun merchants(): ApiResult<List<Merchant>> = fake.merchants()
    override suspend fun recommend(merchantId: Long, amount: Long): ApiResult<Recommendation> = fake.recommend(merchantId, amount)

    /** 서버에 알리지 못해 결제는 확정 마감까지 요청 상태로 남는다 */
    override suspend fun reject(paymentId: Long, idempotencyKey: String): ApiResult<Unit> = ApiResult.Success(Unit)
    /** 방금 승인한 결제는 진짜 영수증 — 가짜 내역과 번호가 겹쳐도 남의 결제가 나오지 않게 먼저 본다 */
    override suspend fun detail(paymentId: Long): ApiResult<PaymentReceipt> =
        real.recentReceipt(paymentId)?.let { ApiResult.Success(it) } ?: fake.detail(paymentId)
}

/** 진짜: 기기 목록 · 해제. 가짜: 회원 정보 · 설정 · 알림 */
class RealMyWithFakes @Inject constructor(
    private val real: MyRepositoryImpl,
    fake: FakeMyRepository,
) : MyRepository by fake {
    override suspend fun devices(): ApiResult<List<Device>> = real.devices()
    override suspend fun revokeDevice(id: Long, stepUpToken: String): ApiResult<Unit> = real.revokeDevice(id, stepUpToken)
}
