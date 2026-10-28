package com.example.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkCheckTest {
    private val fp = "49b193240ab845858c7f54d22264d70e05ad1d82070ef7ea0941b180c2147eb0"

    @Test
    fun matchingSignerPasses() {
        assertTrue(signerMatches(fp, listOf(fp)))
        assertTrue(signerMatches(fp.uppercase().chunked(2).joinToString(":"), listOf(fp)))
    }

    @Test
    fun otherSignerFails() {
        assertFalse(signerMatches(fp, listOf("0".repeat(64))))
        assertFalse(signerMatches(fp, emptyList()))
    }

    @Test
    fun rotatedKeyHistoryStillMatches() {
        assertTrue(signerMatches(fp, listOf("1".repeat(64), fp)))
    }

    @Test
    fun noApprovedSignerMeansNothingToEnforce() {
        assertTrue(signerMatches(null, emptyList()))
    }

    @Test
    fun sha256HexOfKnownInput() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256Hex("abc".toByteArray()))
    }
}
