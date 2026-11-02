package com.yamu.app.feature.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yamu.app.core.network.HealthApi
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import java.io.IOException
import javax.inject.Inject
import kotlin.time.TimeSource

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
            val started = TimeSource.Monotonic.markNow()
            _check.value = try {
                // OkHttp 시간 제한은 DNS 조회를 끊지 못해서 화면이 '확인 중'에 묶이지 않게 코루틴으로 제한한다
                val response = withTimeout(CHECK_TIMEOUT_MILLIS) { health.health() }
                if (response.isSuccessful) {
                    ServerCheck.Ok(response.body()?.status ?: "본문 없음", started.elapsedNow().inWholeMilliseconds)
                } else {
                    ServerCheck.Fail("HTTP ${response.code()} — 주소 경로(/api/)나 서버 버전을 확인해요")
                }
            } catch (e: TimeoutCancellationException) {
                ServerCheck.Fail("${CHECK_TIMEOUT_MILLIS / 1_000}초 안에 응답이 없어요 — 주소를 확인해요")
            } catch (e: IOException) {
                ServerCheck.Fail(e.message ?: e.javaClass.simpleName)   // 연결 거부 · 시간 초과 · 주소 못 찾음
            } catch (e: SerializationException) {
                ServerCheck.Fail("응답 형식이 달라요 — YAMU BE 주소가 맞는지 확인해요")
            }
        }
    }

    internal companion object {
        const val CHECK_TIMEOUT_MILLIS = 10_000L
    }
}
