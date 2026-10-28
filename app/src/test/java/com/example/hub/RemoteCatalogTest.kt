package com.example.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature

class RemoteCatalogTest {
    @Test
    fun signatureMustMatchAKey() {
        val gen = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
        val ours = gen.generateKeyPair()
        val theirs = gen.generateKeyPair()
        val data = """{"apps":[]}""".toByteArray()
        val sig = Signature.getInstance("SHA256withRSA").run { initSign(ours.private); update(data); sign() }
        assertTrue(catalogSignatureValid(data, sig, listOf(theirs.public, ours.public)))
        assertFalse(catalogSignatureValid(data, sig, listOf(theirs.public)))
        assertFalse(catalogSignatureValid("""{"apps":[1]}""".toByteArray(), sig, listOf(ours.public)))
        assertFalse(catalogSignatureValid(data, sig, emptyList()))
        assertFalse(catalogSignatureValid(data, byteArrayOf(1, 2, 3), listOf(ours.public)))
    }

    @Test
    fun ecSignaturesWork() {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val data = "x".toByteArray()
        val sig = Signature.getInstance("SHA256withECDSA").run { initSign(pair.private); update(data); sign() }
        assertTrue(catalogSignatureValid(data, sig, listOf(pair.public)))
    }

    @Test
    fun onlyANewerCatalogOfTheSameSchemaIsUsed() {
        val current = CatalogMeta(1, "2026-10-23", 17, catalogVersion = 100)
        assertTrue(shouldUseRemoteCatalog(current.copy(catalogVersion = 101), current))
        assertFalse(shouldUseRemoteCatalog(current.copy(catalogVersion = 100), current))
        assertFalse(shouldUseRemoteCatalog(current.copy(catalogVersion = 99), current))
        assertFalse(shouldUseRemoteCatalog(current.copy(schemaVersion = 2, catalogVersion = 101), current))
        // The first catalog a device gets is always used.
        assertTrue(shouldUseRemoteCatalog(current, null))
    }

    @Test
    fun artworkPathsStayInsideTheCatalogFolder() {
        assertTrue(safeArtworkPath("artwork/app.png"))
        assertTrue(safeArtworkPath("artwork/com.app.banner.png"))
        assertFalse(safeArtworkPath("../catalog.json"))
        assertFalse(safeArtworkPath("artwork/../../prefs.xml"))
        assertFalse(safeArtworkPath("/data/x.png"))
        assertFalse(safeArtworkPath("artwork//x.png"))
        assertFalse(safeArtworkPath(""))
    }

    @Test
    fun catalogVersionIsReadFromTheHeader() {
        assertEquals(42L, parseCatalogMeta("""{"schemaVersion":1,"catalogVersion":42,"apps":[]}""").catalogVersion)
        assertEquals(0L, parseCatalogMeta("""{"schemaVersion":1,"apps":[]}""").catalogVersion)
    }
}
