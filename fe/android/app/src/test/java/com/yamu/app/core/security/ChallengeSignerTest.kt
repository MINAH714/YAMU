package com.yamu.app.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** BE DeviceSignatureVerifier와 같은 규칙인지 — 일반 EC P-256 키로(Keystore는 기기에서만) */
class ChallengeSignerTest {
    private val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val challenge = "q3Z8vYx1_-AbCdEfGhIjKlMnOpQrStUvWxYz0123456"   // 서버 challenge 모양(base64url 43자)

    private fun sign(text: String) = ChallengeSigner.sign(Signature.getInstance(ChallengeSigner.ALGORITHM).apply { initSign(keys.private) }, text)

    private fun verifies(signed: ByteArray, signatureBase64: String): Boolean =
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(keys.public)
            update(signed)
            verify(Base64.getDecoder().decode(signatureBase64))   // 서버처럼 표준 Base64로 푼다
        }

    @Test fun `challenge 문자열의 UTF-8 바이트 그대로 서명한다`() {
        val signature = sign(challenge)
        assertTrue(verifies(challenge.toByteArray(Charsets.UTF_8), signature))
    }

    @Test fun `challenge를 Base64로 풀어서 서명하지 않는다`() {
        val signature = sign(challenge)
        assertFalse(verifies(Base64.getUrlDecoder().decode(challenge), signature))
    }

    @Test fun `서명 · 공개키는 줄바꿈 없는 표준 Base64`() {
        val signature = sign(challenge)
        assertFalse(signature.contains('\n') || signature.contains('-') || signature.contains('_'))
        val encoded = ChallengeSigner.encodePublicKey(keys.public)
        val decoded = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(encoded)))
        assertEquals(keys.public, decoded)
    }
}
