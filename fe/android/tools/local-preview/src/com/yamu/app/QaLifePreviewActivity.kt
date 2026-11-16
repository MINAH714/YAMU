package com.yamu.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.yamu.app.core.designsystem.theme.YamuTheme
import com.yamu.app.core.security.DeviceKeyManager
import com.yamu.app.core.security.SessionStore
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Local build only: provision a fixture device, then use the unchanged PIN login. */
@AndroidEntryPoint
class QaLifePreviewActivity : FragmentActivity() {
    @Inject lateinit var sessions: SessionStore
    @Inject lateinit var keys: DeviceKeyManager
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { YamuTheme { androidx.compose.material3.Text("로컬 로그인 준비 중") } }
        lifecycleScope.launch {
            try {
                val data=withContext(Dispatchers.IO) {
                    val connection=java.net.URL("http://localhost:18550/local/bootstrap-device").openConnection() as java.net.HttpURLConnection
                    connection.requestMethod="POST"
                    connection.doOutput=true
                    connection.setRequestProperty("Content-Type","application/json")
                    val payload=org.json.JSONObject().put("deviceId",sessions.deviceId()).put("publicKey",keys.devicePublicKey())
                    connection.outputStream.use { it.write(payload.toString().toByteArray()) }
                    org.json.JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                }
                sessions.saveDevice(data.getLong("userDeviceId"),false)
                sessions.saveUser(data.getLong("userId"),data.getString("name"),null,true)
                startActivity(Intent(this@QaLifePreviewActivity,MainActivity::class.java))
                finish()
            } catch(error:Exception) {
                setContent { YamuTheme { androidx.compose.material3.Text("로컬 로그인 준비 실패: "+error.javaClass.simpleName) } }
            }
        }
    }
}
