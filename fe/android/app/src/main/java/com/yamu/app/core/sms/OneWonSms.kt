package com.yamu.app.core.sms

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.os.BundleCompat
import com.google.android.gms.auth.api.phone.SmsRetriever
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status

/** 1원 문자 기다리기 — [start]는 1원을 요청하기 **직전**에(문자가 먼저 오면 놓친다) */
class OneWonSmsListener internal constructor(private val context: Context) {
    /** 5분 동안 문자 1통 대기. Google Play 서비스가 없으면 아무 일도 안 함(직접 입력) */
    fun start() {
        runCatching { SmsRetriever.getClient(context).startSmsUserConsent(null) }
    }
}

/**
 * C-02b 시스템 창(SMS User Consent): 문자가 오면 '읽어도 될까요?' 창 → [허용]이면 그 1통에서 숫자를 꺼내 [onCode].
 * READ_SMS 권한은 쓰지 않는다. 화면이 떠 있는 동안만 받는다.
 */
@Composable
fun rememberOneWonSms(prefix: String, length: Int, onCode: (String) -> Unit): OneWonSmsListener {
    val context = LocalContext.current
    val latestOnCode by rememberUpdatedState(onCode)
    val latestPrefix by rememberUpdatedState(prefix)
    val latestLength by rememberUpdatedState(length)
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult   // [거부] — 직접 입력
        val message = result.data?.getStringExtra(SmsRetriever.EXTRA_SMS_MESSAGE)
        parseOneWonCode(message, latestPrefix, latestLength)?.let(latestOnCode)
    }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != SmsRetriever.SMS_RETRIEVED_ACTION) return
                val extras = intent.extras ?: return
                val status = BundleCompat.getParcelable(extras, SmsRetriever.EXTRA_STATUS, Status::class.java)
                if (status?.statusCode != CommonStatusCodes.SUCCESS) return            // 5분 지남 등
                val ask = BundleCompat.getParcelable(extras, SmsRetriever.EXTRA_CONSENT_INTENT, Intent::class.java) ?: return
                runCatching { consent.launch(ask) }
            }
        }
        // Play 서비스만 보낼 수 있는 방송(SEND_PERMISSION)
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(SmsRetriever.SMS_RETRIEVED_ACTION),
            SmsRetriever.SEND_PERMISSION,
            null,
            ContextCompat.RECEIVER_EXPORTED,
        )
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    return remember(context) { OneWonSmsListener(context) }
}
