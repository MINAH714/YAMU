package com.yamu.app.core.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
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
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * 기기 키 두 개(API 명세 「기기 등록」) — Android Keystore의 EC P-256 서명 키. 공개키만 서버로 보내고 개인키는 기기 밖으로 나가지 않는다.
 * - 기기 키: 지문 없이 서명한다. PIN 로그인 · PIN 본인 확인 때 "등록된 그 폰"임을 증명
 * - 지문 키: 지문(BIOMETRIC_STRONG)을 통과해야만 서명한다. 지문을 새로 등록하면 무효가 된다
 */
@Singleton
class DeviceKeyManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val deviceAlias = "yamu_device_sign_key"
    private val biometricAlias = "yamu_biometric_key"
    private val legacyAlias = "yamu_device_key"   // 예전 단일 키(지문이 있어야 서명) — 새 방식에서는 쓰지 않는다

    /** 이 기기에서 강한 생체 인증(지문 등)을 쓸 수 있나 — 등록된 지문이 없으면 false */
    fun canUseBiometric(): Boolean =
        BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    /** 지문만 보낸다 — 얼굴은 시연 기기에서 BIOMETRIC_STRONG이 아니라 뺐다(DEC-012) */
    fun biometricTypes(): List<String> = listOf("FINGERPRINT")

    fun deviceName(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    fun hasDeviceKey(): Boolean = keyStore().containsAlias(deviceAlias)

    fun hasBiometricKey(): Boolean = keyStore().containsAlias(biometricAlias)

    /** 기기 키 공개키(X.509 DER Base64) — 없으면 만든다. 기기 등록 때마다 같은 키를 다시 보낸다 */
    fun devicePublicKey(): String {
        val ks = keyStore()
        if (ks.containsAlias(legacyAlias)) ks.deleteEntry(legacyAlias)
        val key = ks.getCertificate(deviceAlias)?.publicKey ?: generate(deviceAlias, biometric = false)
        return ChallengeSigner.encodePublicKey(key)
    }

    /** 지문 키를 새로 만들고 공개키를 돌려준다(예전 지문 키는 지움) */
    fun createBiometricKey(): String {
        deleteBiometricKey()
        return ChallengeSigner.encodePublicKey(generate(biometricAlias, biometric = true))
    }

    fun deleteBiometricKey() {
        val ks = keyStore()
        if (ks.containsAlias(biometricAlias)) ks.deleteEntry(biometricAlias)
    }

    /** 기기 키 · 지문 키 모두 — 서버에서 이 기기가 없어졌을 때(DEVICE_NOT_REGISTERED) */
    fun deleteAll() {
        val ks = keyStore()
        listOf(deviceAlias, biometricAlias, legacyAlias).forEach { if (ks.containsAlias(it)) ks.deleteEntry(it) }
    }

    /** challenge를 기기 키로 서명(PIN 로그인 · PIN 본인 확인). 기기 키가 없으면 null */
    fun signWithDeviceKey(challenge: String): String? {
        val key = keyStore().getKey(deviceAlias, null) as? PrivateKey ?: return null
        return ChallengeSigner.sign(Signature.getInstance(ChallengeSigner.ALGORITHM).apply { initSign(key) }, challenge)
    }

    /**
     * 지문 창에 넘길 서명 객체(지문 키). 키가 없거나 무효(지문 새로 등록)면 null — 그땐 간편 비밀번호로.
     */
    fun biometricSignatureForPrompt(): Signature? = try {
        val key = keyStore().getKey(biometricAlias, null) as? PrivateKey ?: return null
        Signature.getInstance(ChallengeSigner.ALGORITHM).apply { initSign(key) }
    } catch (e: KeyPermanentlyInvalidatedException) {
        deleteBiometricKey()
        null
    }

    private fun generate(alias: String, biometric: Boolean): PublicKey {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .apply {
                if (biometric) {
                    setUserAuthenticationRequired(true)
                    setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)   // 매번 지문
                    setInvalidatedByBiometricEnrollment(true)
                }
            }
            .build()
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        generator.initialize(spec)
        return generator.generateKeyPair().public
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
}

/**
 * challenge 서명 규칙 — BE DeviceSignatureVerifier와 같게.
 * 받은 challenge 문자열의 UTF-8 바이트를 그대로(Base64로 풀지 않음) SHA256withECDSA로 서명하고, DER 서명을 표준 Base64(줄바꿈 없음)로.
 * 공개키도 X.509 DER을 같은 Base64로.
 */
object ChallengeSigner {
    const val ALGORITHM = "SHA256withECDSA"

    fun bytes(challenge: String): ByteArray = challenge.toByteArray(Charsets.UTF_8)

    /** initSign이 끝난 서명 객체로 challenge에 서명 */
    fun sign(signature: Signature, challenge: String): String {
        signature.update(bytes(challenge))
        return Base64.getEncoder().encodeToString(signature.sign())
    }

    fun encodePublicKey(key: PublicKey): String = Base64.getEncoder().encodeToString(key.encoded)
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
 * 지문이 맞아야 Keystore 지문 키가 열리고, 그 키로만 서명된다.
 */
suspend fun FragmentActivity.signWithBiometric(
    title: String,
    subtitle: String?,
    negativeText: String,
    signature: Signature,
    challenge: String,
): BiometricOutcome = suspendCancellableCoroutine { cont ->
    val prompt = BiometricPrompt(
        this,
        ContextCompat.getMainExecutor(this),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val outcome = runCatching {
                    val s = result.cryptoObject?.signature ?: error("서명 객체 없음")
                    BiometricOutcome.Signed(ChallengeSigner.sign(s, challenge))
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
