package com.example.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature

class LicenseTest {
    private val pair: KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
    private val day = 24L * 60 * 60 * 1000

    // Signs like the license server: WebCrypto's raw r||s, 32 bytes each.
    private fun sign(payload: ByteArray, keys: KeyPair = pair): ByteArray {
        val der = Signature.getInstance("SHA256withECDSA").run { initSign(keys.private); update(payload); sign() }
        fun fixed(i: BigInteger) = i.toByteArray().let { b -> if (b.size > 32) b.copyOfRange(b.size - 32, b.size) else ByteArray(32 - b.size) + b }
        // DER: 30 len 02 rlen r 02 slen s
        val rLen = der[3].toInt()
        val r = BigInteger(1, der.copyOfRange(4, 4 + rLen))
        val s = BigInteger(1, der.copyOfRange(6 + rLen, der.size))
        return fixed(r) + fixed(s)
    }

    private fun payload(plan: String = "monthly", expires: Long = 2_000, issued: Long = 1_000) =
        """{"d":"dev","p":"$plan","e":$expires,"i":$issued}""".toByteArray()

    @Test
    fun serverSignedTokenVerifies() {
        // Many signatures, so r or s with the top bit set (DER needs a leading 0) is covered too.
        repeat(40) {
            val data = payload()
            assertEquals(LicenseToken("dev", Plan.MONTHLY, 2_000, 1_000), verifyLicense(data, sign(data), pair.public))
        }
    }

    @Test
    fun tamperedOrForeignTokensAreRefused() {
        val data = payload()
        val sig = sign(data)
        assertNull(verifyLicense(payload(plan = "lifetime"), sig, pair.public))
        val other = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        assertNull(verifyLicense(data, sign(data, other), pair.public))
        assertNull(verifyLicense(data, sig.copyOf(63), pair.public))
        val unknownPlan = payload(plan = "forever")
        assertNull(verifyLicense(unknownPlan, sign(unknownPlan), pair.public))
    }

    @Test
    fun onlineAnyUnexpiredTokenForThisDeviceUnlocks() {
        val trial = LicenseToken("dev", Plan.TRIAL, expiresAt = 10 * day, issuedAt = 0)
        assertTrue(tokenUnlocks(trial, "dev", now = day, lastContact = day, online = true))
        assertFalse(tokenUnlocks(trial, "other", now = day, lastContact = day, online = true))
        assertFalse(tokenUnlocks(trial, "dev", now = 10 * day, lastContact = day, online = true))
    }

    @Test
    fun offlineOnlyPaidPlansWorkForAWeek() {
        val paid = LicenseToken("dev", Plan.LIFETIME, expiresAt = 100 * day, issuedAt = 0)
        val trial = paid.copy(plan = Plan.TRIAL)
        assertFalse(tokenUnlocks(trial, "dev", now = day, lastContact = day, online = false))
        assertTrue(tokenUnlocks(paid, "dev", now = 3 * day, lastContact = day, online = false))
        assertFalse(tokenUnlocks(paid, "dev", now = 8 * day + 1, lastContact = day, online = false))
    }

    @Test
    fun offlineAClockWoundBackDoesNotUnlock() {
        val paid = LicenseToken("dev", Plan.MONTHLY, expiresAt = 100 * day, issuedAt = 0)
        assertFalse(tokenUnlocks(paid, "dev", now = 5 * day, lastContact = 6 * day, online = false))
        // A small correction (time zone, network time) is fine.
        assertTrue(tokenUnlocks(paid, "dev", now = 6 * day - CLOCK_SLACK_MS / 2, lastContact = 6 * day, online = false))
    }

    @Test
    fun deviceCodesAreShownInTwoHalves() {
        assertEquals("K7P-4QX", formatDeviceCode("K7P4QX"))
        assertEquals("ABC", formatDeviceCode("ABC"))
    }
}
