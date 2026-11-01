package com.yamu.app

import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import com.yamu.app.core.designsystem.theme.YamuTheme
import com.yamu.app.core.security.SessionStore
import com.yamu.app.core.security.TokenStore
import com.yamu.app.navigation.YamuApp
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : FragmentActivity() {   // 지문 창(BiometricPrompt)이 FragmentActivity를 요구

    @Inject lateinit var tokenStore: TokenStore
    @Inject lateinit var sessionStore: SessionStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()   // 상태 · 내비 바 뒤까지 그리기 — 여백은 화면마다 처리
        if (BuildConfig.DEBUG) {
            Log.d("YAMU", "BASE_URL=${BuildConfig.BASE_URL} real=${BuildConfig.REAL_API}")
        }
        setContent {
            YamuTheme {
                YamuApp(sessionExpired = tokenStore.sessionExpired, autoLockSeconds = sessionStore.autoLockSeconds)
            }
        }
    }
}
