package com.flowreader.app.util

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class SpeechPluginArchiveTest {
    private val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val dex = "dex\n035\u0000payload".toByteArray()

    private fun bundle(
        version: String = "1.0.0", api: String = "1", signer: KeyPair = key,
        corrupt: Boolean = false, extra: Boolean = false, missing: Boolean = false
    ): ByteArray {
        val properties = "id=system-tts\napi=$api\nversion=$version\nentry=${SpeechPluginArchive.ENTRY_CLASS}\n".toByteArray()
        val signed = ByteArrayOutputStream().also { output ->
            DataOutputStream(output).use { it.writeInt(properties.size); it.write(properties); it.writeInt(dex.size); it.write(dex) }
        }.toByteArray()
        val signature = Signature.getInstance("SHA256withRSA").run { initSign(signer.private); update(signed); sign() }
        return ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                val entries = mutableListOf("plugin.properties" to properties, "classes.dex" to if (corrupt) dex + 1 else dex)
                if (!missing) entries += "signature.bin" to signature
                if (extra) entries += "../escape.dex" to dex
                entries.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
            }
        }.toByteArray()
    }

    private fun rejects(bytes: ByteArray, keys: List<java.security.PublicKey> = listOf(key.public)) {
        try { SpeechPluginArchive.verify(bytes, keys); fail("Untrusted component accepted") }
        catch (_: IllegalArgumentException) { }
        catch (_: IllegalStateException) { }
    }

    @Test fun signedPluginPreservesVersionCodeAndStableDigest() {
        val bytes = bundle()
        val first = SpeechPluginArchive.verify(bytes, listOf(key.public))
        assertEquals("1.0.0", first.version)
        assertArrayEquals(dex, first.dex)
        assertEquals(first.digest, SpeechPluginArchive.verify(bytes, listOf(key.public)).digest)
        assertEquals(64, first.digest.length)
    }
    @Test fun tamperedCodeIsRejected() = rejects(bundle(corrupt = true))
    @Test fun anotherPublisherIsRejected() = rejects(bundle(), listOf(KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public))
    @Test fun unsupportedApiIsRejectedEvenWhenSigned() = rejects(bundle(api = "2"))
    @Test fun pathTraversalAndExtraEntriesAreRejected() = rejects(bundle(extra = true))
    @Test fun unsignedArchivesAreRejected() = rejects(bundle(missing = true))
    @Test fun invalidVersionIsRejected() = rejects(bundle(version = "latest"))
    @Test fun oversizedDownloadsAreStopped() {
        try { SpeechPluginArchive.readCapped(ByteArray(30).inputStream(), 20); fail("Size limit ignored") }
        catch (_: IllegalStateException) { }
    }
    @Test fun downloadProgressTracksBytes() {
        val sizes = mutableListOf<Int>()
        val bytes = ByteArray(17000)
        assertArrayEquals(bytes, SpeechPluginArchive.readCapped(bytes.inputStream(), progress = { sizes += it }))
        assertEquals(bytes.size, sizes.last())
    }
}
