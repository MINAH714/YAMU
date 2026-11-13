package com.yamu.app.core.ui

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleStartEffect

/**
 * 인터넷이 끊겨 마지막 값을 보여 주는 동안([waiting]) 연결이 다시 생기면 [onReconnect]를 한 번 부른다(S15P31E103-464).
 * 당겨서 새로고침하지 않아도 '인터넷 연결을 확인해 주세요' 줄이 사라지게 하려는 것.
 *
 * 등록할 때 이미 인터넷이 되면 부르지 않는다. 서버 오류처럼 연결과 상관없는 실패에서 '다시 불러오기 → 또 실패 → 다시 등록'이
 * 되풀이되지 않게, 끊긴 것을 본 뒤 다시 인터넷이 확인될 때만 부른다([ReconnectGate]).
 *
 * 화면이 보이는 동안(STARTED)만 건다. 앱이 내려가 있을 때 연결이 돌아오면 부르지 않고, 앱으로 돌아오면 홈 · 잔고예보가
 * 돌아올 때마다 하는 다시 불러오기(LifecycleResumeEffect)가 맡는다 — 둘 다 부르면 같은 요청이 두 번 나간다
 */
@Composable
fun ReloadOnReconnect(waiting: Boolean, onReconnect: () -> Unit) {
    if (!waiting) return
    val context = LocalContext.current
    val latest by rememberUpdatedState(onReconnect)
    LifecycleStartEffect(context) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val main = Handler(Looper.getMainLooper())
        val reload = Runnable { latest() }
        val gate = ReconnectGate(startedOffline = cm?.let { !it.isValidated(it.activeNetwork) } ?: true)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) = gate.lost()

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (!gate.backOnline(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))) return
                main.removeCallbacks(reload)
                main.postDelayed(reload, RECONNECT_DELAY_MS)   // 연결 직후 DNS · 라우팅이 잡힐 틈
            }
        }
        val registered = cm != null && runCatching { cm.registerDefaultNetworkCallback(callback) }.isSuccess
        onStopOrDispose {
            main.removeCallbacks(reload)
            if (registered) runCatching { cm?.unregisterNetworkCallback(callback) }
        }
    }
}

/** 끊김을 본 뒤 인터넷이 확인되면(VALIDATED) 한 번만 true — 다시 끊겼다 돌아오면 또 한 번. 안드로이드 없이 시험하려고 뺐다 */
internal class ReconnectGate(startedOffline: Boolean) {
    @Volatile private var offline = startedOffline

    fun lost() {
        offline = true
    }

    fun backOnline(validated: Boolean): Boolean {
        if (!validated || !offline) return false
        offline = false
        return true
    }
}

private fun ConnectivityManager.isValidated(network: Network?): Boolean =
    network != null && getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true

private const val RECONNECT_DELAY_MS = 800L
