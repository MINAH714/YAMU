package com.yamu.app

import android.app.Application
import com.yamu.app.core.notify.YamuChannels
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class YamuApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        YamuChannels.create(this)   // 알림 채널(결제 · 실적 · 잔액 · 혜택 · 보안) — 이미 있으면 그대로
    }
}
