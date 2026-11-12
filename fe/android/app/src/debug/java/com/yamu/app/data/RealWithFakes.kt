package com.yamu.app.data

import com.yamu.app.core.network.ApiResult
import com.yamu.app.feature.forecast.data.CashflowDeleteScope
import com.yamu.app.feature.forecast.data.CashflowEntry
import com.yamu.app.feature.forecast.data.CashflowForm
import com.yamu.app.feature.forecast.data.CashflowUpdateRequest
import com.yamu.app.feature.forecast.data.ForecastRepository
import com.yamu.app.feature.forecast.data.ForecastRepositoryImpl
import com.yamu.app.feature.forecast.data.MatchCandidates
import com.yamu.app.feature.forecast.data.MatchConfirmed
import com.yamu.app.feature.forecast.data.PaymentMatch
import com.yamu.app.feature.payment.data.PaymentRepository
import com.yamu.app.feature.payment.data.PaymentRepositoryImpl
import java.time.LocalDate
import javax.inject.Inject

// debug 전용 — yamu.realApi로 켠 기능 중 BE에 아직 없는 API만 가짜로 메운다. release에는 들어가지 않는다.
// BE에 API가 생기면 여기 줄을 지운다. 지울 게 없어지면 클래스째 지우고 DataModule이 Impl을 바로 쓰게 한다.
// 기본 위임은 항상 real이다. 메서드가 늘면 진짜로 가서 BE에 없으면 404로 드러난다.

/**
 * 진짜: 결제 코드 발급 · 상태 · 승인 · 결제 상세 · 취소 · 메모. 가짜: 거절.
 * 결제 상세는 진짜여야 한다 — 가짜 상세에서 본 번호로 진짜 취소 · 메모가 나가면 같은 번호의 내 실제 결제가 바뀐다
 */
class RealPaymentWithFakes @Inject constructor(
    real: PaymentRepositoryImpl,
) : PaymentRepository by real {
    /** 서버에 알리지 못해 결제는 확정 마감까지 요청 상태로 남는다 */
    override suspend fun reject(paymentId: Long, idempotencyKey: String): ApiResult<Unit> = ApiResult.Success(Unit)
}

/**
 * 진짜: 가계부 등록 · 목록 · 수정 · 삭제 · 결제 연결. 가짜: 잔고예보 · 생활비 상세 · 구매 판단 · 구매 계획.
 * 계획 지우기는 가짜로 둔다 — 가짜 계획 번호로 진짜 DELETE를 부르면 같은 번호의 내 항목이 지워질 수 있다
 */
class RealCashflowWithFakes @Inject constructor(
    private val real: ForecastRepositoryImpl,
    fake: FakeForecastRepository,
) : ForecastRepository by fake {
    override suspend fun createItem(form: CashflowForm, idempotencyKey: String): ApiResult<Long> = real.createItem(form, idempotencyKey)
    override suspend fun cashflowEntries(from: LocalDate, to: LocalDate): ApiResult<List<CashflowEntry>> = real.cashflowEntries(from, to)
    override fun cachedEntry(itemId: Long, occurrenceDate: LocalDate): CashflowEntry? = real.cachedEntry(itemId, occurrenceDate)
    override suspend fun updateItem(itemId: Long, body: CashflowUpdateRequest): ApiResult<Unit> = real.updateItem(itemId, body)
    override suspend fun deleteItem(itemId: Long, scope: CashflowDeleteScope, occurrenceDate: LocalDate?): ApiResult<Unit> =
        real.deleteItem(itemId, scope, occurrenceDate)

    // 지난 회차 · 결제 연결도 진짜 가계부 번호를 쓰므로 진짜로(가짜는 진짜 항목 번호를 모른다). 생활비 상세는 잔고예보라 가짜
    override suspend fun pastCardEntries(from: LocalDate, to: LocalDate): ApiResult<List<CashflowEntry>> = real.pastCardEntries(from, to)
    override suspend fun matchStatus(itemId: Long, occurrenceDate: LocalDate): ApiResult<PaymentMatch?> = real.matchStatus(itemId, occurrenceDate)
    override suspend fun matchCandidates(itemId: Long, occurrenceDate: LocalDate): ApiResult<MatchCandidates> = real.matchCandidates(itemId, occurrenceDate)
    override suspend fun confirmMatch(itemId: Long, occurrenceDate: LocalDate, paymentId: Long): ApiResult<MatchConfirmed> =
        real.confirmMatch(itemId, occurrenceDate, paymentId)
    override suspend fun deleteMatch(matchId: Long): ApiResult<Unit> = real.deleteMatch(matchId)
}

// 마이(RealMyWithFakes)는 회원 정보 · 결제 설정 · 보안 설정 API(BE UserController · PaymentSettingsController)가 생겨 지웠다 —
// yamu.realApi=my면 MyRepositoryImpl을 그대로 쓴다(S15P31E103-453). home을 켜지 않으면 홈 종 숫자는 가짜 홈의 값이라 알림함 숫자와 다를 수 있다
