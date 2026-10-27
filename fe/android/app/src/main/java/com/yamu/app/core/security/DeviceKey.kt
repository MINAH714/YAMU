package com.yamu.app.core.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * 기기 키(API 명세 5장) — Android Keystore의 EC P-256 서명 키.
 * 지문(BIOMETRIC_STRONG)을 통과해야만 서명할 수 있고, 지문을 새로 등록하면 키가 무효가 된다.
 * 공개키만 서버(#15)로 보낸다. 개인키는 기기 밖으로 나가지 않는다.
 */
@Singleton
class DeviceKeyManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val alias = "yamu_device_key"

    /** 이 기기에서 강한 생체 인증(지문 등)을 쓸 수 있나 — 등록된 지문이 없으면 false */
    fun canUseBiometric(): Boolean =
        BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    fun biometricTypes(): List<String> = buildList {
        val pm = context.packageManager
        if (pm.hasSystemFeature("android.hardware.fingerprint")) add("FINGERPRINT")
        if (pm.hasSystemFeature("android.hardware.biometrics.face")) add("FACE")
        if (isEmpty()) add("FINGERPRINT")
    }

    fun deviceName(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    fun hasKey(): Boolean = keyStore().containsAlias(alias)

    /** 새 키 쌍을 만들고 공개키(X.509 DER)를 Base64로 — #15 publicKey */
    fun createKey(): String {
        deleteKey()
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        generator.initialize(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(true)
                .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)   // 매번 지문
                .setInvalidatedByBiometricEnrollment(true)
                .build(),
        )
        return Base64.encodeToString(generator.generateKeyPair().public.encoded, Base64.NO_WRAP)
    }

    /**
     * 지문 창에 넘길 서명 객체. 키가 없거나 무효(지문 새로 등록)면 null — 그땐 간편 비밀번호로.
     */
    fun signatureForPrompt(): Signature? = try {
        val key = keyStore().getKey(alias, null) as? PrivateKey ?: return null
        Signature.getInstance("SHA256withECDSA").apply { initSign(key) }
    } catch (e: KeyPermanentlyInvalidatedException) {
        deleteKey()
        null
    }

    fun deleteKey() {
        val ks = keyStore()
        if (ks.containsAlias(alias)) ks.deleteEntry(alias)
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
}

/** 지문 창 결과 */
sealed interface BiometricOutcome {
    data class Signed(val signatureBase64: String) : BiometricOutcome
    data object Cancelled : BiometricOutcome
    /** 창 아래 버튼('간편 비밀번호 사용')을 누름 */
    data object UsePin : BiometricOutcome
    data class Failed(val message: String) : BiometricOutcome
}

/**
 * 시스템 지문 창(BiometricPrompt)을 띄우고 challenge에 서명한다.
 * 지문이 맞아야 Keystore 키가 열리고, 그 키로만 서명된다.
 */
suspend fun FragmentActivity.signWithBiometric(
    title: String,
    subtitle: String?,
    negativeText: String,
    signature: Signature,
    challenge: ByteArray,
): BiometricOutcome = suspendCancellableCoroutine { cont ->
    val prompt = BiometricPrompt(
        this,
        ContextCompat.getMainExecutor(this),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val outcome = runCatching {
                    val s = result.cryptoObject?.signature ?: error("서명 객체 없음")
                    s.update(challenge)
                    BiometricOutcome.Signed(Base64.encodeToString(s.sign(), Base64.NO_WRAP))
                }.getOrElse { BiometricOutcome.Failed("서명하지 못했어요") }
                if (cont.isActive) cont.resume(outcome)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                val outcome = when (errorCode) {
                    BiometricPrompt.ERROR_NEGATIVE_BUTTON -> BiometricOutcome.UsePin
                    BiometricPrompt.ERROR_USER_CANCELED, BiometricPrompt.ERROR_CANCELED -> BiometricOutcome.Cancelled
                    else -> BiometricOutcome.Failed(errString.toString())
                }
                if (cont.isActive) cont.resume(outcome)
            }
            // onAuthenticationFailed: 지문이 안 맞음 — 시스템 창이 그대로 다시 받으니 여기선 아무것도 안 함
        },
    )
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle(title)
        .apply { if (subtitle != null) setSubtitle(subtitle) }
        .setNegativeButtonText(negativeText)
        .setAllowedAuthenticators(BIOMETRIC_STRONG)
        .build()
    prompt.authenticate(info, BiometricPrompt.CryptoObject(signature))
    cont.invokeOnCancellation { prompt.cancelAuthentication() }
}
