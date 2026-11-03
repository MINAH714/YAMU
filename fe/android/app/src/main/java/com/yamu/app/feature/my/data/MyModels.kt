package com.yamu.app.feature.my.data

import com.yamu.app.core.format.KST
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.TextStyle
import java.util.Locale

// ── 서버 요청 · 응답 — #23 · #38/#39 · #52/#53 · #41 · #42/#16 · #49/#50/#51. 13 문서 9·10-3 ──

@Serializable data class UserInfoDto(
    val userId: Long? = null,
    val email: String? = null,
    val name: String? = null,
    val securitySettings: SecuritySettingsDto? = null,   // v0.3.1
    val paymentSettings: PaymentSettingsDto? = null,     // v0.3.1
)

@Serializable data class SecuritySettingsDto(
    val autoLockSeconds: Int = 60,
    val barcodeAuthMethod: String = "BIOMETRIC_OR_PIN",  // BIOMETRIC_OR_PIN · PIN_ONLY
    val biometricEnabled: Boolean = false,
    val currentDevice: CurrentDeviceDto? = null,
)

@Serializable data class CurrentDeviceDto(val userDeviceId: Long? = null, val deviceName: String? = null)

@Serializable data class PaymentSettingsDto(val autoSelect: Boolean = true, val defaultUserCardId: Long? = null)

@Serializable data class PaymentSettingsPatch(val autoSelect: Boolean)

@Serializable data class SecurityPatch(val autoLockSeconds: Int? = null, val barcodeAuthMethod: String? = null)

@Serializable data class NotificationSettingsDto(
    val performance: ToggleTimeDto? = null,
    val balance: BalanceToggleDto? = null,
    val payment: PaymentToggleDto? = null,
)

@Serializable data class ToggleTimeDto(val enabled: Boolean = true, val time: String? = null)

@Serializable data class BalanceToggleDto(val enabled: Boolean = true, val alertThreshold: Long? = null, val time: String? = null)

@Serializable data class PaymentToggleDto(val result: Boolean = true)

@Serializable data class DevicesDto(val items: List<DeviceDto> = emptyList())

@Serializable data class DeviceDto(
    val userDeviceId: Long,
    val deviceName: String? = null,
    val platform: String? = null,
    val isCurrent: Boolean = false,
    val status: String? = null,
    val lastUsedAt: String? = null,
)

@Serializable data class NotificationsDto(
    val unreadCount: Int = 0,
    val items: List<NotificationDto> = emptyList(),
    val hasNext: Boolean = false,
)

@Serializable data class NotificationDto(
    val notificationId: Long,
    val type: String? = null,
    val title: String,
    val body: String? = null,
    val refType: String? = null,
    val refId: Long? = null,
    val isRead: Boolean? = null,
    val readAt: String? = null,
    val createdAt: String? = null,
)

// ── 앱 안에서 쓰는 값 ──

data class MyInfo(
    val name: String,
    val email: String?,
    val autoLockSeconds: Int,
    val pinOnlyForCode: Boolean,
    val biometricEnabled: Boolean,
    val currentDeviceId: Long?,
    val autoSelect: Boolean,
)

/** 알림 종류(M-04) — 보안은 끌 수 없어 여기 없음 */
enum class NotiKind { PERFORMANCE, BALANCE, PAYMENT }

data class NotiSettings(
    val performance: Boolean,
    val balance: Boolean,
    val balanceThreshold: Long?,
    val payment: Boolean,
    val time: String,
)

data class Device(val id: Long, val name: String, val current: Boolean, val lastUsedIso: String?)

/** 알림을 누르면 갈 곳 */
sealed interface NotiTarget {
    data class Card(val userCardId: Long) : NotiTarget
    data class Payment(val paymentId: Long) : NotiTarget
    data object Forecast : NotiTarget
    data object Limits : NotiTarget            // 혜택 한도(R-06) — '카페 할인 한도가 … 남았어요'
    data object None : NotiTarget
}

data class AppNotification(
    val id: Long,
    val title: String,
    val body: String?,
    val read: Boolean,
    val createdIso: String?,
    val target: NotiTarget,
)

data class NotificationsPage(val unread: Int, val items: List<AppNotification>, val hasNext: Boolean)

// ── 서버 값 → 화면 값 ──

/** 탈퇴를 막는 서버 코드 → 문장. 모르는 코드면 null */
fun withdrawErrorMessage(code: String): String? = when (code) {
    "BILL_OUTSTANDING" -> "출금이 끝나지 않은 청구가 있어 탈퇴할 수 없어요"
    "REFUND_PENDING" -> "입금되지 않은 환급이 있어 탈퇴할 수 없어요"
    else -> null
}

/** 'yamu.kim@example.com' → 'ya***@example.com' */
fun maskEmail(email: String?): String? {
    if (email.isNullOrBlank()) return null
    val at = email.indexOf('@')
    if (at <= 0) return email
    return email.take(minOf(2, at)) + "***" + email.substring(at)
}

fun UserInfoDto.toMyInfo() = MyInfo(
    name = name ?: "야무",
    email = email,
    autoLockSeconds = securitySettings?.autoLockSeconds ?: 60,
    pinOnlyForCode = securitySettings?.barcodeAuthMethod.equals("PIN_ONLY", ignoreCase = true),
    biometricEnabled = securitySettings?.biometricEnabled ?: false,
    currentDeviceId = securitySettings?.currentDevice?.userDeviceId,
    autoSelect = paymentSettings?.autoSelect ?: true,
)

fun NotificationSettingsDto.toSettings() = NotiSettings(
    performance = performance?.enabled ?: true,
    balance = balance?.enabled ?: true,
    balanceThreshold = balance?.alertThreshold,
    payment = payment?.result ?: true,
    time = performance?.time ?: balance?.time ?: "08:30",
)

/** #53 부분 수정 — 바꿀 칸만(배열이 아닌 값이라 그대로 덮어씀) */
fun notificationToggleBody(kind: NotiKind, enabled: Boolean): JsonObject = buildJsonObject {
    when (kind) {
        NotiKind.PERFORMANCE -> putJsonObject("performance") { put("enabled", enabled) }
        NotiKind.BALANCE -> putJsonObject("balance") { put("enabled", enabled) }
        NotiKind.PAYMENT -> putJsonObject("payment") { put("result", enabled) }
    }
}

fun notificationTimeBody(time: String): JsonObject = buildJsonObject {
    putJsonObject("performance") { put("time", time) }
    putJsonObject("balance") { put("time", time) }
}

/** '08:30' → '오전 8:30' */
fun koreanTime(hhmm: String): String {
    val (h, m) = hhmm.split(":").mapNotNull { it.toIntOrNull() }.let { (it.getOrNull(0) ?: 8) to (it.getOrNull(1) ?: 30) }
    val ampm = if (h < 12) "오전" else "오후"
    val hour = when {
        h == 0 -> 12
        h > 12 -> h - 12
        else -> h
    }
    return "$ampm $hour:${"%02d".format(m)}"
}

fun NotificationDto.toNotification() = AppNotification(
    id = notificationId,
    title = title,
    body = body,
    read = isRead ?: (readAt != null),
    createdIso = createdAt,
    target = when (refType?.uppercase()) {
        "USER_CARD" -> refId?.let { NotiTarget.Card(it) } ?: NotiTarget.None
        "PAYMENT_TRANSACTION" -> refId?.let { NotiTarget.Payment(it) } ?: NotiTarget.None
        "CARD_BILL", "BALANCE", "FORECAST" -> NotiTarget.Forecast
        "BENEFIT_LIMIT" -> NotiTarget.Limits
        else -> NotiTarget.None
    },
)

/** 알림 묶음 — 오늘 · 이번 주(7일 안) · 이전 */
fun notificationGroup(createdIso: String?, today: LocalDate = LocalDate.now(KST)): String {
    val day = createdIso?.let { runCatching { OffsetDateTime.parse(it).atZoneSameInstant(KST).toLocalDate() }.getOrNull() } ?: return "이전"
    return when {
        day == today -> "오늘"
        !day.isBefore(today.minusDays(6)) -> "이번 주"
        else -> "이전"
    }
}

private fun kstOf(iso: String?) = iso?.let { runCatching { OffsetDateTime.parse(it).atZoneSameInstant(KST) }.getOrNull() }

private fun week(day: LocalDate) = day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)

/** 다른 기기 줄(M-06) — '10월 12일 (월) 21:14 접속' */
fun deviceLastUsed(iso: String?): String? {
    val t = kstOf(iso) ?: return null
    return "${t.monthValue}월 ${t.dayOfMonth}일 (${week(t.toLocalDate())}) ${"%02d:%02d".format(t.hour, t.minute)} 접속"
}

/** 알림 줄 시각(H-02) — 오늘 '08:30' · 어제 '어제 12:30' · 그 전 '10/14 (수) 08:30' */
fun notificationTime(iso: String?, today: LocalDate = LocalDate.now(KST)): String? {
    val t = kstOf(iso) ?: return null
    val hm = "%02d:%02d".format(t.hour, t.minute)
    val day = t.toLocalDate()
    return when (day) {
        today -> hm
        today.minusDays(1) -> "어제 $hm"
        else -> "${day.monthValue}/${day.dayOfMonth} (${week(day)}) $hm"
    }
}

/** 앱 자동 잠금 선택지(M-08) — 0 바로 · 60 1분 · 300 5분 */
val AUTO_LOCK_CHOICES = listOf(0 to "바로", 60 to "1분", 300 to "5분")

fun autoLockLabel(seconds: Int): String =
    AUTO_LOCK_CHOICES.firstOrNull { it.first == seconds }?.second ?: if (seconds < 60) "${seconds}초" else "${seconds / 60}분"
