package com.yamu.app

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

/** Must match QaAccountDevices.Device.challenge() in BE; this data has no PIN/private key. */
object QaAccountDeviceProof {
    const val PREFIX = "YAMU-QA550-PREP-V1"
    val datasets = setOf("qa-550-life-1", "qa-550-life-2", "qa-550-life-3")

    fun challenge(dataset: String, userId: Long, deviceId: String, publicKey: String, nonce: String, issuedAt: String): String =
        listOf(PREFIX, dataset, userId.toString(), deviceId, publicKey, nonce, issuedAt).joinToString("\n")

    fun fingerprint(publicKey: String): String = MessageDigest.getInstance("SHA-256")
        .digest(Base64.getDecoder().decode(publicKey)).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun bytes(challenge: String): ByteArray = challenge.toByteArray(StandardCharsets.UTF_8)

    /** ASCII transport avoids JSON quote mangling across PowerShell, adb and Android shell. */
    fun decodeReceipt(encoded: String): String {
        require(encoded.length <= 87384)
        val decoded = Base64.getUrlDecoder().decode(encoded)
        require(decoded.size <= 65536)
        return String(decoded, StandardCharsets.UTF_8)
    }

    data class Binding(val dataset: String, val userId: Long, val deviceId: String, val keyFingerprint: String)
    data class Receipt(val binding: Binding, val registeredDeviceId: Long, val prepared: Boolean)
    data class LocalState(val deviceId: String, val keyFingerprint: String, val userId: Long?,
                          val registeredDeviceId: Long?, val hasRefreshToken: Boolean)

    /** Import changes only the local device number; every identity field must agree first. */
    fun validateReceipt(request: Binding, receipt: Receipt, local: LocalState): Long {
        require(request.dataset in datasets && request.userId > 0)
        require(receipt.prepared && receipt.registeredDeviceId > 0 && receipt.binding == request)
        require(local.deviceId == request.deviceId && local.keyFingerprint == request.keyFingerprint)
        require(local.userId == null && !local.hasRefreshToken)
        require(local.registeredDeviceId == null || local.registeredDeviceId == receipt.registeredDeviceId)
        return receipt.registeredDeviceId
    }
}
