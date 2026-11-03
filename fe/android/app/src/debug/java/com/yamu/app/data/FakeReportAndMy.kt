package com.yamu.app.data

import android.os.Build
import com.yamu.app.core.format.KST
import com.yamu.app.core.network.ApiError
import com.yamu.app.core.network.ApiResult
import com.yamu.app.core.security.SessionStore
import com.yamu.app.feature.link.data.brandOf
import com.yamu.app.feature.my.data.AppNotification
import com.yamu.app.feature.my.data.Device
import com.yamu.app.feature.my.data.MyInfo
import com.yamu.app.feature.my.data.MyRepository
import com.yamu.app.feature.my.data.NotiKind
import com.yamu.app.feature.my.data.NotiSettings
import com.yamu.app.feature.my.data.NotiTarget
import com.yamu.app.feature.my.data.NotificationsPage
import com.yamu.app.feature.payment.data.PaymentReceipt
import com.yamu.app.feature.payment.data.ReceiptBenefit
import com.yamu.app.feature.report.data.BenefitReport
import com.yamu.app.feature.report.data.CategoryShare
import com.yamu.app.feature.report.data.MissedBenefit
import com.yamu.app.feature.report.data.MonthlySpend
import com.yamu.app.feature.report.data.PaymentItem
import com.yamu.app.feature.report.data.PaymentsPage
import com.yamu.app.feature.report.data.ReportRepository
import com.yamu.app.feature.report.data.periodLabel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton

/** 오늘 기준 날짜 + 시각 → ISO(KST) */
private fun at(daysAgo: Long, hhmm: String): String =
    LocalDate.now(KST).minusDays(daysAgo).atTime(LocalTime.parse(hhmm)).atOffset(ZoneOffset.ofHours(9)).toString()

/** 가짜 결제 내역(S-05) — 프로토타입 줄 + 그 전 날짜는 비슷한 줄로 채워 67건. 거래 상세(P-08)도 이 값으로 */
object FakePaymentHistory {
    /** [benefit] = 내역에 혜택이 붙는 줄의 혜택 이름(시드 benefit_name) */
    private class CardInfo(val id: Long, val issuer: String, val short: String, val color: String, val name: String, val last4: String, val benefit: String?, val check: Boolean = false) {
        val brand get() = brandOf(issuer, shortName = short, color = color)
    }

    // 가짜 은행(FakeBank) 카드 6장과 같은 userCardId — KB국민이 두 장(톡톡 · 노리2)이라 카드사가 아니라 번호로 찾는다
    private val CARDS = listOf(
        CardInfo(11, "KB국민카드", "KB국민", "#FFBC00", "KB국민 청춘대로 톡톡카드", "1021", "스타벅스 50% 청구할인"),
        CardInfo(12, "현대카드", "현대", "#1A1A1A", "현대카드ZERO Edition3(포인트형)", "3310", "1.2% M포인트 적립"),
        CardInfo(13, "롯데카드", "롯데", "#E60012", "LOCA LIKIT Shop", "7782", "쿠팡·편의점 60% 할인"),
        CardInfo(14, "삼성카드", "삼성", "#1428A0", "삼성 iD ON 카드", "4410", "교통 10% 결제일할인"),
        CardInfo(15, "신한카드", "신한", "#0046FF", "신한카드 Mr.Life", "5096", "주말 마트 10% 할인"),
        CardInfo(16, "KB국민카드", "KB국민", "#60584C", "KB국민 노리2 체크카드(KB Pay)", "8823", "GS25·CU 5% 할인", check = true),
    ).associateBy { it.id }

    /** 결제 번호 → 카드 번호(거래 상세가 같은 카드를 보이게) */
    private val cardOf = mutableMapOf<Long, Long>()

    /** 오늘 줄(09:38)이 아직 오지 않은 시각이면 전부 하루 앞으로 — 방금 한 가짜 결제보다 뒤에 놓이지 않게 */
    private val dayShift: Long = if (LocalTime.now(KST).isBefore(LocalTime.of(10, 8))) 1 else 0

    private fun item(id: Long, daysAgo: Long, hhmm: String, merchant: String, cardId: Long, amount: Long, benefit: Long? = null, label: String = "할인", excluded: Boolean = false): PaymentItem {
        cardOf[id] = cardId
        return PaymentItem(id, LocalDate.now(KST).minusDays(daysAgo + dayShift), hhmm, merchant, CARDS.getValue(cardId).brand, amount, benefit, label, excluded, canceled = false)
    }

    // 혜택은 카드 시드 비율 — 톡톡 스타벅스 50% · iD ON 교통 10% · ZERO 1.2% 적립 · Mr.Life 주말 마트 10%(월 한도 3,000)
    val seed: List<PaymentItem> by lazy {
        val first = listOf(
            item(938, 0, "09:38", "스타벅스 역삼점", 11, 15_000, 7_500),
            item(937, 0, "08:12", "지하철 (대중교통)", 14, 1_450, 145),
            item(936, 1, "19:42", "야무치킨 역삼점", 13, 22_000),
            item(935, 1, "18:50", "역삼 생활용품점", 12, 39_000, 468, "적립"),
            item(934, 1, "12:30", "스타벅스 테헤란점", 11, 4_500, 2_250),
            item(933, 1, "12:05", "역삼 백반집", 12, 24_000, 288, "적립"),
            item(932, 2, "20:15", "동네마트 역삼점", 15, 32_000, 3_000),
            item(931, 2, "12:40", "역삼 국수집", 12, 9_000),
            item(930, 2, "12:10", "모바일 문화상품권", 13, 50_000, excluded = true),
            item(929, 2, "08:05", "역삼 베이커리", 12, 5_500, 66, "적립"),
        )
        val names = listOf("GS25 역삼역점" to 16L, "야무주유소 선릉점" to 15L, "역삼 분식" to 11L, "온라인 야무몰" to 13L, "버스 (대중교통)" to 14L)
        val more = (0 until 57).map { i ->
            val (name, cardId) = names[i % names.size]
            item(928L - i, 3L + i / 4, listOf("08:40", "12:20", "18:30", "21:10")[i % 4], name, cardId, listOf(6_800L, 41_000L, 8_500L, 23_900L, 1_450L)[i % 5])
        }
        first + more
    }

    private fun categoryOf(merchant: String) = when {
        "카페" in merchant || "베이커리" in merchant || "스타벅스" in merchant -> "카페"
        "GS25" in merchant -> "편의점"
        "대중교통" in merchant -> "대중교통"
        "마트" in merchant -> "마트"
        "편의점" in merchant -> "편의점"
        "주유" in merchant -> "주유"
        "몰" in merchant -> "온라인 쇼핑"
        "상품권" in merchant -> "상품권"
        "생활용품" in merchant -> "생활"
        else -> "음식점"
    }

    /** 내역 줄과 같은 거래 상세 — 없는 번호면 null */
    fun receipt(paymentId: Long): PaymentReceipt? {
        val p = seed.firstOrNull { it.paymentId == paymentId } ?: return null
        val card = CARDS.getValue(cardOf[paymentId] ?: 11)
        val day = p.date ?: LocalDate.now(KST)
        val time = p.time ?: "12:00"
        val benefit = p.benefit ?: 0
        return PaymentReceipt(
            paymentId = p.paymentId, merchantName = p.merchant, categoryName = categoryOf(p.merchant), amount = p.amount,
            paidAtIso = day.atTime(LocalTime.parse(time)).atOffset(ZoneOffset.ofHours(9)).toString(),
            cardId = card.id, cardBrand = p.brand, cardName = card.name, cardLast4 = card.last4, isCheck = card.check,
            approvalNo = "Y" + day.toString().replace("-", "") + time.replace(":", "") + "00" + p.paymentId,
            installmentMonths = null,
            benefits = if (benefit > 0) listOf(ReceiptBenefit(card.benefit ?: (categoryOf(p.merchant) + " " + p.benefitLabel), benefit)) else emptyList(),
            chargedAmount = p.amount, balanceAfter = null,
            billDateIso = if (card.check) null else YearMonth.now(KST).plusMonths(1).atDay(14).toString(),   // 체크카드는 바로 출금 — 청구 없음
            billAmount = if (card.check) null else if (p.benefitLabel == "할인") p.amount - benefit else p.amount,
            perfMonth = day.monthValue, perfRecognized = if (p.excluded) 0 else p.amount, perfCounted = !p.excluded,
            perfTotalAfter = if (p.paymentId == 938L) 285_000 else null, perfTarget = if (p.paymentId == 938L) 300_000 else null,   // 938 = 홈 최근 결제(P-07 숫자)
        )
    }
}

/**
 * debug 전용 가짜 소비분석 — 프로토타입 S-01 · S-04 · S-05 숫자(10월 1~16일 → 오늘 기준). 이번 실행에서 가짜 결제를 하면 맨 위 · 합계에 더한다.
 */
@Singleton
class FakeReportRepository @Inject constructor(private val payments: FakePaymentRepository) : ReportRepository {

    private val seed get() = FakePaymentHistory.seed

    private fun sessionItems(): List<PaymentItem> = payments.approved().map { r ->
        val time = r.paidAtIso?.let { OffsetDateTime.parse(it).atZoneSameInstant(KST) }
        PaymentItem(
            r.paymentId, time?.toLocalDate(), time?.toLocalTime()?.toString()?.take(5), r.merchantName ?: "결제", r.cardBrand, r.amount,
            r.benefits.sumOf { it.amount }.takeIf { it > 0 }, "할인", excluded = !r.perfCounted, canceled = false,
        )
    }

    override suspend fun monthly(): ApiResult<MonthlySpend> = withFault(empty = { ApiResult.Success(MonthlySpend(periodLabel(YearMonth.now(KST).toString(), partial = true), YearMonth.now(KST).monthValue, isLiving = true, spent = 0, diffFromPrev = null, categories = emptyList(), benefit = 0, paymentCount = 0)) }) { monthlyNow() }

    private suspend fun monthlyNow(): ApiResult<MonthlySpend> {
        delay(500)
        val extra = sessionItems()
        return ApiResult.Success(
            MonthlySpend(
                periodLabel = periodLabel(YearMonth.now(KST).toString(), partial = true),
                month = YearMonth.now(KST).monthValue,
                isLiving = true,
                spent = 842_000 + extra.sumOf { it.amount },
                diffFromPrev = 48_000 + extra.sumOf { it.amount },
                categories = listOf(CategoryShare("식비", 29, 248_000), CategoryShare("쇼핑", 20, 172_000), CategoryShare("주유·마트", 16, 138_000)),
                benefit = 32_400 + extra.sumOf { it.benefit ?: 0 },
                paymentCount = seed.size + extra.size,
            ),
        )
    }

    override suspend fun benefits(): ApiResult<BenefitReport> = withFault(empty = { ApiResult.Success(BenefitReport(periodLabel(YearMonth.now(KST).toString(), partial = true), received = 0, yamuExtra = null, missedTotal = 0, missed = emptyList(), byPlace = emptyList())) }) { benefitsNow() }

    private suspend fun benefitsNow(): ApiResult<BenefitReport> {
        delay(500)
        val extra = sessionItems().sumOf { it.benefit ?: 0 }
        return ApiResult.Success(
            BenefitReport(
                periodLabel = periodLabel(YearMonth.now(KST).toString(), partial = true),
                received = 32_400 + extra,
                yamuExtra = 5_200,
                missedTotal = 2_980,
                missed = listOf(
                    MissedBenefit("동네마트 역삼점", LocalDate.now(KST).minusDays(5), "자동 선택 꺼짐", 2_080),
                    MissedBenefit("편의점 테헤란점", LocalDate.now(KST).minusDays(8), "직접 고른 카드", 900),
                ),
                byPlace = listOf("생활·렌탈" to 10_000L, "마트·주유" to 8_300L, "카페" to 5_280L + extra),
            ),
        )
    }

    override suspend fun payments(page: Int): ApiResult<PaymentsPage> = withFault(empty = { ApiResult.Success(PaymentsPage(count = 0, amount = 0, items = emptyList(), page = page, hasNext = false)) }) { paymentsNow(page) }

    private suspend fun paymentsNow(page: Int): ApiResult<PaymentsPage> {
        delay(400)
        val all = sessionItems() + seed
        val size = 20
        val from = (page - 1) * size
        if (from > all.size) return ApiResult.Failure(ApiError.InvalidRequest("INVALID_PAGE"))
        val items = all.drop(from).take(size)
        return ApiResult.Success(PaymentsPage(all.size, 2_164_200 + sessionItems().sumOf { it.amount }, items, page, hasNext = from + size < all.size))
    }
}

/** 가짜 서버의 결제 코드 인증 방식(M-08) — 가짜 인증(FakeAuthRepository)이 같이 본다 */
object FakeSecurity {
    @Volatile var pinOnlyForCode = false
}

/**
 * debug 전용 가짜 마이 · 알림 — 설정은 메모리에(앱을 다시 켜면 처음 값), 자동 잠금 시간은 진짜 저장소에도 저장.
 */
@Singleton
class FakeMyRepository @Inject constructor(
    private val session: SessionStore,
    private val payments: FakePaymentRepository,
) : MyRepository {

    private var autoSelect = true
    private var settings = NotiSettings(performance = true, balance = true, balanceThreshold = 100_000, payment = true, time = "08:30")
    private val devices = mutableListOf(
        Device(7, Build.MODEL ?: "이 기기", current = true, lastUsedIso = at(0, "09:00")),
        Device(8, "Chrome · Windows", current = false, lastUsedIso = at(14, "21:14")),
    )
    private val readIds = mutableSetOf<Long>()
    private val seedNotifications = listOf(
        AppNotification(9005, "롯데카드, 추가 지출 없이 채울 수 있어요", "통신비만 롯데카드로 옮기면 40만 원 실적을 채워요", false, at(0, "08:30"), NotiTarget.Card(13)),
        AppNotification(9004, "KB국민카드 스타벅스 할인 한도가 5,800원 남았어요", null, false, at(1, "12:30"), NotiTarget.Limits),
        AppNotification(9003, "삼성카드는 실적을 채우지 않는 게 유리해요", null, false, at(1, "08:30"), NotiTarget.Card(14)),
        AppNotification(9002, "월세 250,000원 출금 예정이에요", "잔고예보에서 월급 전 잔액을 확인해 보세요", true, at(2, "08:30"), NotiTarget.Forecast),
        AppNotification(9001, "새 기기에서 로그인했어요", "Chrome · Windows", true, at(14, "21:14"), NotiTarget.None),
    )

    override suspend fun me(): ApiResult<MyInfo> = withFault { meNow() }

    private suspend fun meNow(): ApiResult<MyInfo> {
        delay(300)
        val s = session.read()
        return ApiResult.Success(
            MyInfo(
                name = s.name ?: "김야무", email = s.email ?: "yamu.kim@example.com",
                autoLockSeconds = session.autoLockSeconds.first(), pinOnlyForCode = FakeSecurity.pinOnlyForCode,
                biometricEnabled = s.biometricEnabled, currentDeviceId = s.userDeviceId, autoSelect = autoSelect,
            ),
        )
    }

    override suspend fun setAutoSelect(on: Boolean): ApiResult<Unit> { delay(300); autoSelect = on; return ApiResult.Success(Unit) }

    override suspend fun setAutoLock(seconds: Int): ApiResult<Unit> { delay(300); session.setAutoLockSeconds(seconds); return ApiResult.Success(Unit) }

    override suspend fun setPinOnlyForCode(pinOnly: Boolean): ApiResult<Unit> { delay(300); FakeSecurity.pinOnlyForCode = pinOnly; return ApiResult.Success(Unit) }

    override suspend fun notificationSettings(): ApiResult<NotiSettings> = withFault {
        delay(300)
        ApiResult.Success(settings)
    }

    override suspend fun setNotification(kind: NotiKind, enabled: Boolean): ApiResult<Unit> {
        delay(300)
        settings = when (kind) {
            NotiKind.PERFORMANCE -> settings.copy(performance = enabled)
            NotiKind.BALANCE -> settings.copy(balance = enabled)
            NotiKind.PAYMENT -> settings.copy(payment = enabled)
        }
        return ApiResult.Success(Unit)
    }

    override suspend fun setNotificationTime(hhmm: String): ApiResult<Unit> { delay(300); settings = settings.copy(time = hhmm); return ApiResult.Success(Unit) }

    override suspend fun devices(): ApiResult<List<Device>> = withFault {
        delay(300)
        ApiResult.Success(devices.toList())
    }

    override suspend fun revokeDevice(id: Long, stepUpToken: String): ApiResult<Unit> { delay(300); devices.removeAll { it.id == id }; return ApiResult.Success(Unit) }

    override suspend fun withdraw(stepUpToken: String): ApiResult<Unit> { delay(500); return ApiResult.Success(Unit) }

    private fun all(): List<AppNotification> {
        val paid = payments.approved().map {
            AppNotification(it.paymentId, "결제 완료 · ${it.merchantName}", "${"%,d".format(it.amount)}원 · ${it.cardName ?: "카드"}", false, it.paidAtIso, NotiTarget.Payment(it.paymentId))
        }
        return (paid + seedNotifications).map { n -> if (n.id in readIds) n.copy(read = true) else n }
    }

    /** 홈 종 숫자(가짜 홈)도 이 값 */
    fun unreadCount(): Int = all().count { !it.read }

    override suspend fun notifications(page: Int): ApiResult<NotificationsPage> = withFault(empty = { ApiResult.Success(NotificationsPage(unread = 0, items = emptyList(), hasNext = false)) }) { notificationsNow(page) }

    private suspend fun notificationsNow(page: Int): ApiResult<NotificationsPage> {
        delay(400)
        val list = all()
        return ApiResult.Success(NotificationsPage(list.count { !it.read }, if (page == 1) list else emptyList(), hasNext = false))
    }

    override suspend fun markRead(id: Long): ApiResult<Unit> { readIds += id; return ApiResult.Success(Unit) }

    override suspend fun markAllRead(): ApiResult<Unit> { delay(300); readIds += all().map { it.id }; return ApiResult.Success(Unit) }
}
