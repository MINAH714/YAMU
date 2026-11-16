package com.yamu.app

import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.yamu.app.core.designsystem.theme.YamuTheme
import com.yamu.app.core.security.SessionStore
import com.yamu.app.core.security.TokenStore
import com.yamu.app.core.security.settleSessionEnd
import com.yamu.app.feature.auth.data.AuthRepository
import com.yamu.app.navigation.StartGate
import com.yamu.app.navigation.YamuApp
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : FragmentActivity() {   // 지문 창(BiometricPrompt)이 FragmentActivity를 요구

    @Inject lateinit var tokenStore: TokenStore
    @Inject lateinit var sessionStore: SessionStore

    /**
     * 진짜 저장소는 만들 때 OkHttp · Retrofit을 함께 만든다(성능 문서 9-3) — 메인 스레드에서 꺼내지 않고 아래에서 Default 스레드로 미리 만든다.
     * 스플래시가 갈 곳을 정할 때(SplashViewModel)는 대개 이미 만들어져 있다(S15P31E103-512)
     */
    @Inject lateinit var auth: dagger.Lazy<AuthRepository>

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()   // super.onCreate보다 먼저(Theme.YAMU.Starting → Theme.YAMU)
        super.onCreate(savedInstanceState)
        // 첫 화면(A-01 · A-03)이 구성될 때까지 시스템 스플래시를 둔다. 화면을 다시 만들 때는 스플래시 경로를 지나지 않으니 바로 연다
        val gate = StartGate().also { if (savedInstanceState != null) it.open() }
        splash.setKeepOnScreenCondition(gate::keep)
        lifecycleScope.launch(Dispatchers.Default) { auth.get() }
        enableEdgeToEdge()   // 상태 · 내비 바 뒤까지 그리기 — 여백은 화면마다 처리
        if (BuildConfig.DEBUG) {
            Log.d("YAMU", "BASE_URL=${BuildConfig.BASE_URL} real=${BuildConfig.REAL_API}")
        }
        setContent {
            YamuTheme {
                YamuApp(
                    sessionEnd = tokenStore.sessionEnd,
                    autoLockSeconds = sessionStore.autoLockSeconds,
                    settleSessionEnd = { event -> tokenStore.settleSessionEnd(event) { auth.get().forgetRevokedDevice() } },
                    onSessionEndHandled = tokenStore::consumeSessionEnd,
                    onStartDecided = gate::open,
                )
            }
        }
    }
}
