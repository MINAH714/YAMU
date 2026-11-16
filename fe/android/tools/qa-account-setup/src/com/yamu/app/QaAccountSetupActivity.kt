package com.yamu.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.yamu.app.core.designsystem.theme.YamuTheme
import com.yamu.app.core.security.DeviceKeyManager
import com.yamu.app.core.security.SessionStore
import com.yamu.app.core.security.TokenStore
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Temporary operator build: export a domain-separated public proof, or import a matching receipt. No HTTP. */
@AndroidEntryPoint
class QaAccountSetupActivity : FragmentActivity() {
    @Inject lateinit var sessions: SessionStore
    @Inject lateinit var keys: DeviceKeyManager
    @Inject lateinit var tokens: TokenStore

    // Export only the public proof; this Activity is included by the explicit debug-only init script.
    private val saveProof = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri, "wt")!!.use { output ->
                        File(filesDir, "qa-account-device.json").inputStream().use { it.copyTo(output) }
                    }
                }
                showStatus("등록 자료를 저장했어요. 운영 담당자에게 전달해 주세요.", true)
            } catch (_: Exception) { showStatus("등록 자료를 저장하지 못했어요. 다시 시도해 주세요.", true) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showStatus("기기 준비 중")
        lifecycleScope.launch {
            try {
                val action = intent.getStringExtra("action") ?: "export"
                require(action in setOf("export", "import"))
                val result = withContext(Dispatchers.IO) {
                    when (action) {
                        "export" -> exportRequest()
                        else -> importReceipt()
                    }
                }
                if (action == "import") {
                    startActivity(Intent(this@QaAccountSetupActivity, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    })
                    finish()
                } else showStatus(result, true)
            } catch (_: Exception) {
                // Exceptions may include malformed input. Show no raw message, token, or signing material.
                showStatus("기기 준비를 완료하지 못했어요. 운영 담당자에게 확인해 주세요.")
            }
        }
    }

    private suspend fun exportRequest(): String {
        val existing = sessions.read()
        require(tokens.refreshTokenBlocking() == null && existing.userDeviceId == null && existing.userId == null)
        val dataset = intent.getStringExtra("datasetKey") ?: error("Missing dataset")
        val userId = intent.getLongExtra("userId", 0)
        require(dataset in QaAccountDeviceProof.datasets && userId > 0)
        val deviceId = sessions.deviceId()
        val publicKey = keys.devicePublicKey()
        val nonce = ByteArray(32).also { SecureRandom().nextBytes(it) }
            .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        val issued = Instant.now().toString()
        val challenge = QaAccountDeviceProof.challenge(dataset, userId, deviceId, publicKey, nonce, issued)
        val signature = keys.signWithDeviceKey(challenge) ?: error("Signing unavailable")
        val request = JSONObject().put("datasetKey", dataset).put("expectedUserId", userId)
            .put("deviceId", deviceId).put("deviceName", keys.deviceName()).put("publicKey", publicKey)
            .put("nonce", nonce).put("issuedAt", issued).put("signature", signature)
        File(filesDir, "qa-account-device.json").writeText(request.toString(), Charsets.UTF_8)
        return "기기 등록 자료를 준비했어요. 운영 도구에서 등록을 진행해 주세요."
    }

    private suspend fun importReceipt(): String {
        require(tokens.refreshTokenBlocking() == null && keys.hasDeviceKey())
        val file = File(filesDir, "qa-account-device.json")
        require(file.isFile && file.length() <= 16384)
        val request = JSONObject(file.readText(Charsets.UTF_8))
        val text = QaAccountDeviceProof.decodeReceipt(intent.getStringExtra("receiptBase64") ?: error("Missing receipt"))
        val receipt = JSONObject(text)
        require(receipt.getInt("version") == 1)
        val targets = receipt.getJSONArray("targets")
        val matching = (0 until targets.length()).map { targets.getJSONObject(it) }
            .filter { it.getString("datasetKey") == request.getString("datasetKey") }
        require(matching.size == 1)
        val target = matching.single()
        require(request.getString("publicKey") == keys.devicePublicKey())
        val existing = sessions.read()
        val registeredId = QaAccountDeviceProof.validateReceipt(
            QaAccountDeviceProof.Binding(request.getString("datasetKey"), request.getLong("expectedUserId"),
                request.getString("deviceId"), QaAccountDeviceProof.fingerprint(request.getString("publicKey"))),
            QaAccountDeviceProof.Receipt(QaAccountDeviceProof.Binding(target.getString("datasetKey"), target.getLong("userId"),
                target.getString("deviceId"), target.getString("publicKeyFingerprint")), target.getLong("userDeviceId"), target.getBoolean("prepared")),
            QaAccountDeviceProof.LocalState(sessions.deviceId(), QaAccountDeviceProof.fingerprint(keys.devicePublicKey()),
                existing.userId, existing.userDeviceId, tokens.refreshTokenBlocking() != null),
        )
        sessions.saveDevice(registeredId, false)
        return "기기 준비를 완료했어요. 간편 비밀번호로 로그인해 주세요."
    }

    private fun showStatus(text: String, canSave: Boolean = false) {
        setContent { YamuTheme { Column {
            Text(text)
            if (canSave) Button(onClick = { saveProof.launch("yamu-device-registration.json") }) {
                Text("등록 자료 저장")
            }
        } } }
    }
}
