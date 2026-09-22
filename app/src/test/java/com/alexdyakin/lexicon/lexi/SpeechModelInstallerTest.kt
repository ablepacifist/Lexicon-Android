package com.alexdyakin.lexicon.lexi

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The speech model is fetched once, over a network that may drop, from a file nobody here
 * controls. So what matters is not the happy path but the rest: a half-finished or wrong
 * download must never be left looking installed, because the failure would then surface
 * much later, inside the speech engine, where the cause is invisible.
 */
class SpeechModelInstallerTest {

    @get:Rule val temp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private val client = OkHttpClient()

    /** A stand-in for Vosk's archive: real zip, same versioned top-level folder. */
    private val archive: ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("vosk-model-small-en-us-0.15/"))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("vosk-model-small-en-us-0.15/conf/model.conf"))
            zip.write("--beam=10.0\n".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("vosk-model-small-en-us-0.15/am/final.mdl"))
            zip.write(ByteArray(4096) { it.toByte() })
            zip.closeEntry()
        }
    }.toByteArray()

    private val archiveSha = MessageDigest.getInstance("SHA-256").digest(archive)
        .joinToString("") { "%02x".format(it) }

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() = server.shutdown()

    private fun installer(sha: String = archiveSha, body: ByteArray? = archive) = SpeechModelInstaller(
        http = client,
        modelDir = File(temp.root, "files/lexi/vosk"),
        workDir = File(temp.root, "cache/lexi-speech-model"),
        url = server.url("/vosk-model.zip").toString(),
        sha256 = sha,
        downloadBytes = (body ?: archive).size.toLong(),
    )

    private fun enqueueArchive() = server.enqueue(MockResponse().setBody(Buffer().write(archive)))

    private val modelDir get() = File(temp.root, "files/lexi/vosk")
    private val workDir get() = File(temp.root, "cache/lexi-speech-model")

    // ── installing ───────────────────────────────────────────────────────────────────

    @Test
    fun `downloads, unpacks and stamps the model`() = runBlocking {
        enqueueArchive()
        val installer = installer()
        assertFalse(installer.isInstalled())

        val dir = installer.ensureInstalled()

        assertEquals(modelDir, dir)
        // The versioned top-level folder is dropped, so a model upgrade does not move
        // everything the engine loads.
        assertEquals("--beam=10.0\n", File(dir, "conf/model.conf").readText())
        assertEquals(4096, File(dir, "am/final.mdl").length())
        assertTrue(installer.isInstalled())
        assertFalse("the archive should not be left behind", workDir.exists())
    }

    @Test
    fun `an installed model is used without touching the network`() = runBlocking {
        enqueueArchive()
        installer().ensureInstalled()

        // Nothing more is enqueued: a second request would hang and fail the test.
        val second = installer()
        assertTrue(second.isInstalled())
        second.ensureInstalled()
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `reports progress from start to finish`() = runBlocking {
        enqueueArchive()
        val seen = ArrayList<Float?>()
        installer().ensureInstalled { seen += it }

        assertEquals(0f, seen.first())
        assertEquals(1f, seen.last()!!, 1e-6f)
        assertTrue("progress should not go backwards", seen.filterNotNull().zipWithNext().all { it.first <= it.second })
    }

    @Test
    fun `replaces a model left over from an older version`() = runBlocking {
        val stale = modelDir.apply { mkdirs() }
        File(stale, "conf").mkdirs()
        File(stale, "conf/model.conf").writeText("old")
        File(stale, "installed.sha256").writeText("a-different-hash")
        File(stale, "leftover.bin").writeText("should be gone")

        enqueueArchive()
        installer().ensureInstalled()

        assertEquals("--beam=10.0\n", File(modelDir, "conf/model.conf").readText())
        assertFalse(File(modelDir, "leftover.bin").exists())
    }

    // ── failures ─────────────────────────────────────────────────────────────────────

    @Test
    fun `a corrupted download installs nothing`() = runBlocking {
        server.enqueue(MockResponse().setBody(Buffer().write("not the model".toByteArray())))
        expectFailure(installer())
    }

    @Test
    fun `a wrong file at the URL installs nothing`() = runBlocking {
        // The pinned hash is what stops a redirect, a captive portal or a replaced upstream
        // file from being installed.
        server.enqueue(MockResponse().setBody(Buffer().write(archive)))
        expectFailure(installer(sha = "0".repeat(64)))
    }

    @Test
    fun `an HTTP error installs nothing`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        expectFailure(installer())
    }

    @Test
    fun `a dropped connection installs nothing, and a retry still works`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setBody(Buffer().write(archive))
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
        )
        expectFailure(installer())

        enqueueArchive()
        installer().ensureInstalled()
        assertTrue(installer().isInstalled())
    }

    @Test
    fun `an archive that writes outside the model directory is refused`() = runBlocking {
        val hostile = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { zip ->
                zip.putNextEntry(ZipEntry("model/../../../escaped.txt"))
                zip.write("nope".toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()
        val sha = MessageDigest.getInstance("SHA-256").digest(hostile).joinToString("") { "%02x".format(it) }
        server.enqueue(MockResponse().setBody(Buffer().write(hostile)))

        expectFailure(installer(sha = sha, body = hostile))
        assertFalse(File(temp.root, "escaped.txt").exists())
    }

    private suspend fun expectFailure(installer: SpeechModelInstaller) {
        try {
            installer.ensureInstalled()
            fail("expected the install to fail")
        } catch (e: IOException) {
            // expected
        }
        assertFalse("a failed install must not look installed", installer.isInstalled())
        assertFalse("no scratch files should be left behind", workDir.exists())
    }
}
