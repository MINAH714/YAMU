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
import javax.inject.Inject

@HiltViewModel
class SplashViewModel @Inject constructor(private val auth: AuthRepository) : ViewModel() {
    suspend fun decide(): SessionStart = auth.sessionStart()
}

/**
 * 시작할 때 갈 곳 정하기(08 문서 4-1). 화면은 Android 12+ 시스템 스플래시(앱 아이콘)를 그대로 쓴다.
 * 이 폰에 등록된 기기(기기 번호 · 기기 키)가 있으면 PIN · 지문 로그인, 없으면 첫 화면. 토큰은 로그인할 때마다 새로 받는다.
 */
@Composable
fun SplashRoute(onDecided: (SessionStart) -> Unit, viewModel: SplashViewModel = hiltViewModel()) {
    LaunchedEffect(Unit) { onDecided(viewModel.decide()) }
    Box(Modifier.fillMaxSize().background(YamuTheme.colors.surface))
}
