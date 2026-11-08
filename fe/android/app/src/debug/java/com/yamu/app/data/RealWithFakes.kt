package com.yamu.app.data

import com.yamu.app.core.network.ApiResult
import com.yamu.app.feature.forecast.data.CashflowDeleteScope
import com.yamu.app.feature.forecast.data.CashflowEntry
import com.yamu.app.feature.forecast.data.CashflowForm
import com.yamu.app.feature.forecast.data.CashflowUpdateRequest
import com.yamu.app.feature.forecast.data.ForecastRepository
import com.yamu.app.feature.forecast.data.ForecastRepositoryImpl
import com.yamu.app.feature.my.data.Device
import com.yamu.app.feature.my.data.MyInfo
import com.yamu.app.feature.my.data.MyRepository
import com.yamu.app.feature.my.data.MyRepositoryImpl
import com.yamu.app.feature.my.data.NotiKind
import com.yamu.app.feature.my.data.NotiSettings
import com.yamu.app.feature.my.data.NotificationsPage
import com.yamu.app.feature.payment.data.Merchant
import com.yamu.app.feature.payment.data.PaymentRepository
import com.yamu.app.feature.payment.data.PaymentRepositoryImpl
import com.yamu.app.feature.payment.data.Recommendation
import java.time.LocalDate
import javax.inject.Inject

// debug 전용 — yamu.realApi로 켠 기능 중 BE에 아직 없는 API만 가짜로 메운다. release에는 들어가지 않는다.
// BE에 API가 생기면 여기 줄을 지운다. 지울 게 없어지면 클래스째 지우고 DataModule이 Impl을 바로 쓰게 한다.
// 기본 위임은 항상 real이다. 메서드가 늘면 진짜로 가서 BE에 없으면 404로 드러난다.

/**
 * 진짜: 결제 코드 발급 · 상태 · 승인 · 결제 상세 · 취소 · 메모. 가짜: 가맹점 목록 · 결제 전 추천 · 거절.
 * 결제 상세는 진짜여야 한다 — 가짜 상세에서 본 번호로 진짜 취소 · 메모가 나가면 같은 번호의 내 실제 결제가 바뀐다
 */
class RealPaymentWithFakes @Inject constructor(
    real: PaymentRepositoryImpl,
    private val fake: FakePaymentRepository,
) : PaymentRepository by real {
    override suspend fun merchants(): ApiResult<List<Merchant>> = fake.merchants()
    override suspend fun recommend(merchantId: Long, amount: Long): ApiResult<Recommendation> = fake.recommend(merchantId, amount)

    /** 서버에 알리지 못해 결제는 확정 마감까지 요청 상태로 남는다 */
    override suspend fun reject(paymentId: Long, idempotencyKey: String): ApiResult<Unit> = ApiResult.Success(Unit)
}

/**
 * 진짜: 가계부 등록 · 목록 · 수정 · 삭제. 가짜: 잔고예보 · 구매 판단 · 구매 계획.
 * 계획 지우기는 가짜로 둔다 — 가짜 계획 번호로 진짜 DELETE를 부르면 같은 번호의 내 항목이 지워질 수 있다
 */
class RealCashflowWithFakes @Inject constructor(
    private val real: ForecastRepositoryImpl,
    fake: FakeForecastRepository,
) : ForecastRepository by fake {
    override suspend fun createItem(form: CashflowForm): ApiResult<Long> = real.createItem(form)
    override suspend fun cashflowEntries(from: LocalDate, to: LocalDate): ApiResult<List<CashflowEntry>> = real.cashflowEntries(from, to)
    override fun cachedEntry(itemId: Long, occurrenceDate: LocalDate): CashflowEntry? = real.cachedEntry(itemId, occurrenceDate)
    override suspend fun updateItem(itemId: Long, body: CashflowUpdateRequest): ApiResult<Unit> = real.updateItem(itemId, body)
    override suspend fun deleteItem(itemId: Long, scope: CashflowDeleteScope, occurrenceDate: LocalDate?): ApiResult<Unit> =
        real.deleteItem(itemId, scope, occurrenceDate)
}

/** 진짜: 기기 목록 · 해제 · 회원 탈퇴. 가짜: 회원 정보 · 설정 · 알림 */
class RealMyWithFakes @Inject constructor(
    real: MyRepositoryImpl,
    private val fake: FakeMyRepository,
) : MyRepository by real {
    override suspend fun me(): ApiResult<MyInfo> = fake.me()
    override suspend fun setAutoSelect(on: Boolean): ApiResult<Unit> = fake.setAutoSelect(on)
    override suspend fun setAutoLock(seconds: Int): ApiResult<Unit> = fake.setAutoLock(seconds)
    override suspend fun setPinOnlyForCode(pinOnly: Boolean): ApiResult<Unit> = fake.setPinOnlyForCode(pinOnly)
    override suspend fun notificationSettings(): ApiResult<NotiSettings> = fake.notificationSettings()
    override suspend fun setNotification(kind: NotiKind, enabled: Boolean): ApiResult<Unit> = fake.setNotification(kind, enabled)
    override suspend fun setNotificationTime(hhmm: String): ApiResult<Unit> = fake.setNotificationTime(hhmm)
    override suspend fun notifications(page: Int): ApiResult<NotificationsPage> = fake.notifications(page)
    override suspend fun markRead(id: Long): ApiResult<Unit> = fake.markRead(id)
    override suspend fun markAllRead(): ApiResult<Unit> = fake.markAllRead()
}
