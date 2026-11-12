package com.yamu.app

import android.app.Application
import com.yamu.app.core.notify.YamuChannels
import com.yamu.app.core.ui.DemoCapture
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class YamuApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        YamuChannels.create(this)   // 알림 채널(결제 · 실적 · 잔액 · 혜택 · 보안) — 이미 있으면 그대로
        DemoCapture.init(this)      // 시연 모드 캡처 허용(24시간) — 첫 화면보다 먼저 읽어 둔다
    }
}
