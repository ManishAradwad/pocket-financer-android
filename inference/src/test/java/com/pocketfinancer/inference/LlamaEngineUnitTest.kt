package com.pocketfinancer.inference

import android.content.Context
import io.mockk.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LlamaEngineUnitTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var mockContext: Context
    private lateinit var llamaEngine: LlamaEngine

    @Before
    fun setUp() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.i(any(), any()) } returns 0
        every { android.util.Log.e(any(), any(), any()) } returns 0

        mockContext = mockk()
        every { mockContext.filesDir } returns tempFolder.newFolder("filesDir")

        llamaEngine = LlamaEngine(mockContext)
    }

    @After
    fun tearDown() {
        unmockkStatic(android.util.Log::class)
    }

    @Test
    fun testComputeSha256_empty() {
        val hash = llamaEngine.computeSha256("")
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hash)
    }

    @Test
    fun testComputeSha256_hello() {
        val hash = llamaEngine.computeSha256("hello")
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", hash)
    }

    @Test
    fun testComputeSha256_unicode() {
        val hash = llamaEngine.computeSha256("pocket-financer-₹-💰")
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val expectedBytes = md.digest("pocket-financer-₹-💰".toByteArray(Charsets.UTF_8))
        val expectedHash = expectedBytes.joinToString("") { "%02x".format(it) }
        assertEquals(expectedHash, hash)
    }

    @Test
    fun testPerformanceData_tokensPerSecond_normal() {
        val perf = LlamaEngine.PerformanceData(
            tLoadMs = 100,
            tPromptEvalMs = 200,
            tEvalMs = 1000,
            nTokens = 15
        )
        assertEquals(15.0, perf.tokensPerSecond, 0.001)
    }

    @Test
    fun testPerformanceData_tokensPerSecond_zeroEvalTime() {
        val perf = LlamaEngine.PerformanceData(
            tLoadMs = 100,
            tPromptEvalMs = 200,
            tEvalMs = 0,
            nTokens = 15
        )
        assertEquals(0.0, perf.tokensPerSecond, 0.001)
    }

    @Test
    fun testPerformanceData_tokensPerSecond_negativeEvalTime() {
        val perf = LlamaEngine.PerformanceData(
            tLoadMs = 100,
            tPromptEvalMs = 200,
            tEvalMs = -500,
            nTokens = 15
        )
        assertEquals(0.0, perf.tokensPerSecond, 0.001)
    }

    @Test
    fun testModelFilenameConstant() {
        assertEquals("qwen3-1.7b-Q8_0.gguf", LlamaEngine.MODEL_FILENAME)
    }

    @Test
    fun testGetModelStorageDir_createsDirectory() {
        val storageDir = llamaEngine.getModelStorageDir()
        assertTrue(storageDir.exists())
        assertEquals("models", storageDir.name)
    }

    @Test
    fun testGetSessionFile() {
        val hash = "abcd1234"
        val sessionFile = llamaEngine.getSessionFile(hash)
        assertEquals("session_abcd1234.bin", sessionFile.name)
        assertEquals(File(llamaEngine.getModelStorageDir(), "session_abcd1234.bin").absolutePath, sessionFile.absolutePath)
    }

    @Test
    fun testDeleteStaleSessions_emptyDir() {
        llamaEngine.deleteStaleSessions("activehash")
        val storageDir = llamaEngine.getModelStorageDir()
        val files = storageDir.listFiles()
        assertTrue(files == null || files.isEmpty())
    }

    @Test
    fun testDeleteStaleSessions_onlyStale() {
        val storageDir = llamaEngine.getModelStorageDir()
        val stale1 = File(storageDir, "session_stale1.bin").apply { createNewFile() }
        val stale2 = File(storageDir, "session_stale2.bin").apply { createNewFile() }

        assertTrue(stale1.exists())
        assertTrue(stale2.exists())

        llamaEngine.deleteStaleSessions("activehash")

        assertFalse(stale1.exists())
        assertFalse(stale2.exists())
    }

    @Test
    fun testDeleteStaleSessions_keepsActive() {
        val storageDir = llamaEngine.getModelStorageDir()
        val stale = File(storageDir, "session_stale.bin").apply { createNewFile() }
        val active = File(storageDir, "session_activehash.bin").apply { createNewFile() }

        assertTrue(stale.exists())
        assertTrue(active.exists())

        llamaEngine.deleteStaleSessions("activehash")

        assertFalse(stale.exists())
        assertTrue(active.exists())
    }

    @Test
    fun testDeleteStaleSessions_keepsNonSessionFiles() {
        val storageDir = llamaEngine.getModelStorageDir()
        val otherFile = File(storageDir, "other_file.txt").apply { createNewFile() }
        val staleSession = File(storageDir, "session_stale.bin").apply { createNewFile() }

        assertTrue(otherFile.exists())
        assertTrue(staleSession.exists())

        llamaEngine.deleteStaleSessions("activehash")

        assertTrue(otherFile.exists())
        assertFalse(staleSession.exists())
    }

    @Test
    fun testDeleteStaleSessions_nullActiveHash() {
        val storageDir = llamaEngine.getModelStorageDir()
        val session1 = File(storageDir, "session_1.bin").apply { createNewFile() }
        val session2 = File(storageDir, "session_2.bin").apply { createNewFile() }

        assertTrue(session1.exists())
        assertTrue(session2.exists())

        llamaEngine.deleteStaleSessions(null)

        assertFalse(session1.exists())
        assertFalse(session2.exists())
    }
}
