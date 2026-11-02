package com.alexdyakin.lexicon.ui.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** What an update must pass before the installer is shown. */
class UpdateVerificationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val good = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad" // SHA-256 of "abc"

    // ── the published checksum is mandatory ──

    @Test
    fun `a missing or blank checksum refuses the update instead of skipping the check`() {
        for (missing in listOf(null, "", "   ")) {
            val e = assertThrows(IllegalArgumentException::class.java) { UpdateVerification.requireChecksum(missing) }
            assertTrue(e.message!!.contains("no checksum"))
        }
    }

    @Test
    fun `a malformed checksum is refused`() {
        for (bad in listOf("abc", "zz".repeat(32), good.dropLast(1), good + "0")) {
            assertThrows(IllegalArgumentException::class.java) { UpdateVerification.requireChecksum(bad) }
        }
    }

    @Test
    fun `a valid checksum is accepted, trimmed and lowercased`() {
        assertEquals(good, UpdateVerification.requireChecksum("  ${good.uppercase()}\n"))
    }

    // ── hashing ──

    @Test
    fun `sha256Hex matches the known digest`() {
        val f = tmp.newFile("a.apk").apply { writeText("abc") }
        assertEquals(good, UpdateVerification.sha256Hex(f))
    }

    @Test
    fun `a file whose content changed no longer matches`() {
        val f = tmp.newFile("b.apk").apply { writeText("abd") }
        assertFalse(UpdateVerification.sha256Hex(f) == good)
    }

    @Test
    fun `a large file is hashed in pieces`() {
        val f = tmp.newFile("big.apk")
        f.outputStream().use { out -> repeat(300) { out.write(ByteArray(10_000) { 7 }) } }
        assertEquals(64, UpdateVerification.sha256Hex(f).length)
    }

    // ── signers ──

    @Test
    fun `digestsOf hashes each certificate`() {
        val digests = UpdateVerification.digestsOf(listOf("abc".toByteArray()))
        assertEquals(setOf(good), digests)
    }

    @Test
    fun `the same signer is accepted`() {
        assertTrue(UpdateVerification.sharesSigner(setOf("aa"), setOf("aa")))
    }

    @Test
    fun `a rotated key is accepted while the old one is still in the lineage`() {
        assertTrue(UpdateVerification.sharesSigner(setOf("old"), setOf("old", "new")))
    }

    @Test
    fun `a different signer is refused`() {
        assertFalse(UpdateVerification.sharesSigner(setOf("aa"), setOf("bb")))
        assertFalse(UpdateVerification.sharesSigner(setOf("aa", "cc"), setOf("bb")))
    }

    @Test
    fun `a package with no readable signer is refused`() {
        assertFalse(UpdateVerification.sharesSigner(setOf("aa"), emptySet()))
        assertFalse(UpdateVerification.sharesSigner(emptySet(), emptySet()))
    }

    @Test
    fun `if the installed app's own signers cannot be read there is nothing to compare against`() {
        assertTrue(UpdateVerification.sharesSigner(emptySet(), setOf("bb")))
    }
}
