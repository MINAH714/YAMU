package com.yamu.app

import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class QaAccountDeviceProofTest {
    private val binding = QaAccountDeviceProof.Binding("qa-550-life-1", 11, "installation-1", "fingerprint-1")
    private val receipt = QaAccountDeviceProof.Receipt(binding, 77, true)
    private val local = QaAccountDeviceProof.LocalState(binding.deviceId, binding.keyFingerprint, null, null, false)

    @Test fun importsOnlyMatchingPreparedDeviceAndAllowsSameReceiptAgain() {
        assertEquals(77, QaAccountDeviceProof.validateReceipt(binding, receipt, local))
        assertEquals(77, QaAccountDeviceProof.validateReceipt(binding, receipt, local.copy(registeredDeviceId = 77)))
    }

    @Test fun rejectsReceiptForOtherPersonDatasetInstallationOrKey() {
        for (wrong in listOf(binding.copy(userId = 12), binding.copy(dataset = "qa-550-life-2"),
            binding.copy(deviceId = "installation-2"), binding.copy(keyFingerprint = "another-key"))) {
            rejects { QaAccountDeviceProof.validateReceipt(binding, receipt.copy(binding = wrong), local) }
        }
        rejects { QaAccountDeviceProof.validateReceipt(binding, receipt, local.copy(deviceId = "another-installation")) }
        rejects { QaAccountDeviceProof.validateReceipt(binding, receipt, local.copy(keyFingerprint = "new-key")) }
    }

    @Test fun neverOverwritesExistingUserTokenOrDifferentDevice() {
        for (existing in listOf(local.copy(userId = 11), local.copy(hasRefreshToken = true),
            local.copy(registeredDeviceId = 78))) {
            rejects { QaAccountDeviceProof.validateReceipt(binding, receipt, existing) }
        }
    }

    @Test fun rejectsUnpreparedPlanAndMissingDeviceNumber() {
        rejects { QaAccountDeviceProof.validateReceipt(binding, receipt.copy(prepared = false), local) }
        rejects { QaAccountDeviceProof.validateReceipt(binding, receipt.copy(registeredDeviceId = 0), local) }
    }

    @Test fun receiptTransportPreservesJsonAndRejectsOversizedOrInvalidBase64() {
        val json = """{"version":1,"name":"최인국","targets":[]}"""
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray(StandardCharsets.UTF_8))
        assertEquals(json, QaAccountDeviceProof.decodeReceipt(encoded))
        rejects { QaAccountDeviceProof.decodeReceipt("_") }
        rejects { QaAccountDeviceProof.decodeReceipt("A".repeat(87385)) }
    }

    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected identity guard to reject import") } catch (_: IllegalArgumentException) { }
    }

    @Test fun canonicalProofMatchesBackendContractAndRejectsChangedIdentity() {
        val generator = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        val pair = generator.generateKeyPair()
        val publicKey = Base64.getEncoder().encodeToString(pair.public.encoded)
        val args = arrayOf("qa-550-life-1", "00000000-0000-0000-0000-000000000001", publicKey,
            Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32)), "2026-11-16T12:00:00Z")
        val challenge = QaAccountDeviceProof.challenge(args[0], 11, args[1], args[2], args[3], args[4])
        assertEquals("YAMU-QA550-PREP-V1\nqa-550-life-1\n11\n${args[1]}\n$publicKey\n${args[3]}\n${args[4]}", challenge)
        assertArrayEquals(challenge.toByteArray(StandardCharsets.UTF_8), QaAccountDeviceProof.bytes(challenge))
        val signer = Signature.getInstance("SHA256withECDSA").apply { initSign(pair.private); update(QaAccountDeviceProof.bytes(challenge)) }
        val signature = signer.sign()
        fun verifies(value: String) = Signature.getInstance("SHA256withECDSA").run {
            initVerify(pair.public); update(QaAccountDeviceProof.bytes(value)); verify(signature)
        }
        assertTrue(verifies(challenge))
        assertFalse(verifies(QaAccountDeviceProof.challenge(args[0], 12, args[1], args[2], args[3], args[4])))
        assertEquals(64, QaAccountDeviceProof.fingerprint(publicKey).length)
        assertNotEquals(QaAccountDeviceProof.fingerprint(publicKey),
            QaAccountDeviceProof.fingerprint(Base64.getEncoder().encodeToString(generator.generateKeyPair().public.encoded)))
    }
}
