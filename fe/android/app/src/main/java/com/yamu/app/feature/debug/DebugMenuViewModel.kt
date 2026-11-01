package com.yamu.app.feature.debug

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yamu.app.core.network.HealthApi
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import java.io.IOException
import javax.inject.Inject

/** 디버그 메뉴 '서버 연결 확인' 결과 */
sealed interface ServerCheck {
    data object Idle : ServerCheck
    data object Checking : ServerCheck
    data class Ok(val status: String, val millis: Long) : ServerCheck
    data class Fail(val reason: String) : ServerCheck
}

@HiltViewModel
class DebugMenuViewModel @Inject constructor(private val health: HealthApi) : ViewModel() {
    private val _check = MutableStateFlow<ServerCheck>(ServerCheck.Idle)
    val check: StateFlow<ServerCheck> = _check.asStateFlow()

    fun checkServer() {
        if (_check.value == ServerCheck.Checking) return   // 연타
        _check.value = ServerCheck.Checking
        viewModelScope.launch {
            val started = SystemClock.elapsedRealtime()
            _check.value = try {
                val response = health.health()
                if (response.isSuccessful) {
                    ServerCheck.Ok(response.body()?.status ?: "본문 없음", SystemClock.elapsedRealtime() - started)
                } else {
                    ServerCheck.Fail("HTTP ${response.code()} — 주소 경로(/api/)나 서버 버전을 확인해요")
                }
            } catch (e: IOException) {
                ServerCheck.Fail(e.message ?: e.javaClass.simpleName)   // 연결 거부 · 시간 초과 · 주소 못 찾음
            } catch (e: SerializationException) {
                ServerCheck.Fail("응답 형식이 달라요 — YAMU BE 주소가 맞는지 확인해요")
            }
        }
    }
}
