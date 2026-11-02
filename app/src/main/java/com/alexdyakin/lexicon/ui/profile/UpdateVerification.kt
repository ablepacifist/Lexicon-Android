package com.alexdyakin.lexicon.ui.profile

import java.io.File
import java.security.MessageDigest

/**
 * The checks an downloaded update must pass before the installer is shown. Pure functions, so they are tested on the JVM;
 * [AppUpdateManager] supplies the Android-specific inputs (the package archive's signers).
 */
internal object UpdateVerification {

    /**
     * The checksum the server published for this update. A missing one is an error, not "skip the check": the update is
     * then refused rather than installed unverified.
     */
    fun requireChecksum(expected: String?): String {
        val clean = expected?.trim()?.lowercase().orEmpty()
        require(clean.isNotEmpty()) { "The update has no checksum, so it was not installed." }
        require(clean.matches(SHA256_HEX)) { "The update's checksum is malformed, so it was not installed." }
        return clean
    }

    fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Hex SHA-256 of each signing certificate (raw certificate bytes in, hex digests out). */
    fun digestsOf(certificates: Collection<ByteArray>): Set<String> =
        certificates.map { MessageDigest.getInstance("SHA-256").digest(it).joinToString("") { b -> "%02x".format(b) } }.toSet()

    /**
     * Whether the downloaded package is signed by the same key as the installed app. Android's installer enforces this
     * too; checking first means a wrongly-signed file is never even offered to the user.
     *
     * If the installed app's signers cannot be read there is nothing to compare against, so that case is not a failure;
     * a package that has no readable signer at all, or whose signer matches none of ours, is.
     */
    fun sharesSigner(installed: Set<String>, candidate: Set<String>): Boolean {
        if (candidate.isEmpty()) return false
        if (installed.isEmpty()) return true
        return installed.any { it in candidate }
    }

    private val SHA256_HEX = Regex("^[0-9a-f]{64}$")
}
