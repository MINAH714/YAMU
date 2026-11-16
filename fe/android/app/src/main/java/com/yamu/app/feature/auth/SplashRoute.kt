package com.yamu.app.feature.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.yamu.app.core.designsystem.theme.YamuTheme
import com.yamu.app.feature.auth.data.AuthRepository
import com.yamu.app.feature.auth.data.SessionStart
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 진짜 인증 저장소는 만들 때 OkHttp · Retrofit을 함께 만든다(약 10ms, 성능 문서 9-3). MainActivity가 Default 스레드에서 미리 만들어 두고,
 * 아직이면 여기서도 메인 스레드가 아닌 곳에서 기다린다. 갈 곳 정하기는 그대로 부른 쪽에서 한다
 */
@HiltViewModel
class SplashViewModel @Inject constructor(private val auth: dagger.Lazy<AuthRepository>) : ViewModel() {
    suspend fun decide(): SessionStart = withContext(Dispatchers.Default) { auth.get() }.sessionStart()
}

/**
 * 시작할 때 갈 곳 정하기(08 문서 4-1). 정하는 동안은 MainActivity가 시스템 스플래시(앱 아이콘)를 그대로 둔다(S15P31E103-512) —
 * 아래 빈 상자는 정하는 일이 1.5초 넘게 걸릴 때만 보인다([com.yamu.app.navigation.StartGate]).
 * 이 폰에 등록된 기기(기기 번호 · 기기 키)가 있으면 PIN · 지문 로그인, 없으면 첫 화면. 토큰은 로그인할 때마다 새로 받는다.
 */
@Composable
fun SplashRoute(onDecided: (SessionStart) -> Unit, viewModel: SplashViewModel = hiltViewModel()) {
    LaunchedEffect(Unit) { onDecided(viewModel.decide()) }
    Box(Modifier.fillMaxSize().background(YamuTheme.colors.surface))
}
