package com.yamu.app.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

private val Context.tokenDataStore: DataStore<Preferences> by preferencesDataStore(name = "yamu_tokens")
private val REFRESH_TOKEN = stringPreferencesKey("refresh_token_enc")

/**
 * Refresh 토큰은 Android Keystore의 AES-GCM 키로 암호화해서 DataStore에 둔다.
 * (security-crypto의 EncryptedSharedPreferences는 지원 중단이라 쓰지 않음 — 03 문서)
 * 저장 · 지우기 · 비교해서 지우기는 같은 [mutex] 안에서 한다. 늦게 도착한 401의 정리가 그사이 로그인한 새 토큰을 지우지 않게.
 * *Blocking 함수는 OkHttp 스레드에서만 부른다 — 메인 스레드에서 부르면 메인에서 저장을 마무리하려는 쪽과 서로 기다린다
 */
@Singleton
class KeystoreTokenStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : TokenStore {

    private val cipher = KeystoreCipher()
    private val mutex = Mutex()

    @Volatile override var accessToken: String? = null
        private set

    @Volatile private var refreshToken: String? = null

    @Volatile override var tokenVersion: Long = 0
        private set

    private val signal = SessionEndSignal()
    override val sessionEnd: StateFlow<SessionEndEvent?> = signal.pending

    override fun refreshTokenBlocking(): String? {
        refreshToken?.let { return it }
        val encrypted = runBlocking { context.tokenDataStore.data.first()[REFRESH_TOKEN] } ?: return null
        return cipher.decrypt(encrypted).also { refreshToken = it }
    }

    override suspend fun save(tokens: AuthTokens) {
        mutex.withLock {
            accessToken = tokens.accessToken
            refreshToken = tokens.refreshToken
            tokenVersion++
            val encrypted = cipher.encrypt(tokens.refreshToken)
            context.tokenDataStore.edit { it[REFRESH_TOKEN] = encrypted }
        }
    }

    override fun saveBlocking(tokens: AuthTokens) = runBlocking { save(tokens) }

    override suspend fun clear() = mutex.withLock { clearLocked() }

    override fun clearBlocking() = runBlocking { clear() }

    override fun clearIfCurrentBlocking(accessToken: String?): Long? = runBlocking {
        mutex.withLock {
            if (this@KeystoreTokenStore.accessToken != accessToken) null else tokenVersion.also { clearLocked() }
        }
    }

    override suspend fun clearIfVersion(tokenVersion: Long): Boolean = mutex.withLock {
        (this.tokenVersion == tokenVersion).also { same -> if (same) clearLocked() }
    }

    private suspend fun clearLocked() {
        accessToken = null
        refreshToken = null
        context.tokenDataStore.edit { it.remove(REFRESH_TOKEN) }
    }

    override fun notifySessionEnd(event: SessionEndEvent) = signal.publish(event)

    override fun consumeSessionEnd(event: SessionEndEvent) = signal.consume(event)
}

/** Android Keystore AES-256-GCM — 키는 기기 밖으로 나가지 않는다 */
internal class KeystoreCipher(private val alias: String = "yamu_token_key") {

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    fun encrypt(plain: String): String {
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    /** 키가 바뀌었거나 값이 깨졌으면 null(다시 로그인) */
    fun decrypt(encoded: String): String? = runCatching {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, IV_SIZE)))
        String(c.doFinal(bytes.copyOfRange(IV_SIZE, bytes.size)), Charsets.UTF_8)
    }.getOrNull()

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
    }
}
