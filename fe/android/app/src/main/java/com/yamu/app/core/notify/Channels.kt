package com.yamu.app.core.notify

import android.content.Context
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat

/** 알림 채널 — 사용자가 시스템 설정에서 종류별로 끌 수 있게(B-07b). 푸시 연결(FCM)은 11단계 */
object YamuChannels {
    const val PAYMENT = "payment"
    const val PERFORMANCE = "performance"
    const val BALANCE = "balance"
    const val BENEFIT = "benefit"
    const val SECURITY = "security"

    fun create(context: Context) {
        val high = NotificationManagerCompat.IMPORTANCE_HIGH
        val normal = NotificationManagerCompat.IMPORTANCE_DEFAULT
        val channels = listOf(
            Triple(PAYMENT, "결제", high) to "결제 요청 · 결제 결과",
            Triple(PERFORMANCE, "실적", normal) to "실적 마감 · 실적 미달 예측",
            Triple(BALANCE, "잔액", normal) to "잔액 부족 예상",
            Triple(BENEFIT, "혜택", normal) to "받은 혜택 · 남은 한도",
            Triple(SECURITY, "보안", high) to "새 기기 로그인 · 본인 인증 잠김",
        ).map { (c, description) ->
            NotificationChannelCompat.Builder(c.first, c.third).setName(c.second).setDescription(description).build()
        }
        NotificationManagerCompat.from(context).createNotificationChannelsCompat(channels)
    }
}
