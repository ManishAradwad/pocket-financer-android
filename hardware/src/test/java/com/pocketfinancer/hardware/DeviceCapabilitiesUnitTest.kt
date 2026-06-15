package com.pocketfinancer.hardware

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.*

class DeviceCapabilitiesUnitTest {

    private lateinit var mockContext: Context
    private lateinit var mockActivityManager: ActivityManager

    @Before
    fun setUp() {
        mockContext = mockk()
        mockActivityManager = mockk()

        // Mock ActivityManager for RAM checks
        every { mockContext.getSystemService(Context.ACTIVITY_SERVICE) } returns mockActivityManager
        every { mockContext.filesDir } returns File("/data/data/com.pocketfinancer/files")
    }

    // ── ramTierFor (pure logic, no deps) ─────────────────────────────

    @Test
    fun `ramTierFor should return BLOCKED below 2_5GB`() {
        val caps = DeviceCapabilities(mockContext)
        assertEquals(DeviceCapabilities.RamTier.BLOCKED, caps.ramTierFor(1.0f))
        assertEquals(DeviceCapabilities.RamTier.BLOCKED, caps.ramTierFor(2.0f))
        assertEquals(DeviceCapabilities.RamTier.BLOCKED, caps.ramTierFor(2.49f))
    }

    @Test
    fun `ramTierFor should return WARNING between 2_5 and 3_5`() {
        val caps = DeviceCapabilities(mockContext)
        assertEquals(DeviceCapabilities.RamTier.WARNING, caps.ramTierFor(2.5f))
        assertEquals(DeviceCapabilities.RamTier.WARNING, caps.ramTierFor(3.0f))
        assertEquals(DeviceCapabilities.RamTier.WARNING, caps.ramTierFor(3.49f))
    }

    @Test
    fun `ramTierFor should return OK at or above 3_5`() {
        val caps = DeviceCapabilities(mockContext)
        assertEquals(DeviceCapabilities.RamTier.OK, caps.ramTierFor(3.5f))
        assertEquals(DeviceCapabilities.RamTier.OK, caps.ramTierFor(4.0f))
        assertEquals(DeviceCapabilities.RamTier.OK, caps.ramTierFor(8.0f))
    }

    @Test
    fun `ramTierFor should handle exactly at boundary`() {
        val caps = DeviceCapabilities(mockContext)
        // Just below 2.5 = BLOCKED
        assertEquals(DeviceCapabilities.RamTier.BLOCKED, caps.ramTierFor(2.4999f))
        // Exactly 2.5 = WARNING
        assertEquals(DeviceCapabilities.RamTier.WARNING, caps.ramTierFor(2.5f))
        // Exactly 3.5 = OK
        assertEquals(DeviceCapabilities.RamTier.OK, caps.ramTierFor(3.5f))
    }

    // ── checkRamGb (mocked ActivityManager) ──────────────────────────

    @Test
    fun `checkRamGb should return correct GB from ActivityManager`() {
        val caps = DeviceCapabilities(mockContext)
        val memInfoSlot = mutableListOf<ActivityManager.MemoryInfo>()

        every { mockActivityManager.getMemoryInfo(capture(memInfoSlot)) } answers {
            memInfoSlot.last().totalMem = (4L * 1024 * 1024 * 1024) // 4 GB
        }

        val ramGb = caps.checkRamGb()
        assertEquals(4.0f, ramGb, 0.1f)
    }

    @Test
    fun `checkRamGb should handle 6GB device`() {
        val caps = DeviceCapabilities(mockContext)
        val memInfoSlot = mutableListOf<ActivityManager.MemoryInfo>()

        every { mockActivityManager.getMemoryInfo(capture(memInfoSlot)) } answers {
            memInfoSlot.last().totalMem = (6L * 1024 * 1024 * 1024)
        }

        val ramGb = caps.checkRamGb()
        assertEquals(6.0f, ramGb, 0.1f)
    }

    @Test
    fun `checkRamGb should handle 8GB device`() {
        val caps = DeviceCapabilities(mockContext)
        val memInfoSlot = mutableListOf<ActivityManager.MemoryInfo>()

        every { mockActivityManager.getMemoryInfo(capture(memInfoSlot)) } answers {
            memInfoSlot.last().totalMem = (8L * 1024 * 1024 * 1024)
        }

        val ramGb = caps.checkRamGb()
        assertEquals(8.0f, ramGb, 0.1f)
    }

    // ── isHighPerformanceDevice (needs CPU info from /proc/cpuinfo) ──
    // NOTE: getCpuInfo() reads /proc/cpuinfo which is not available in
    // unit tests. This is tested via instrumented tests on device.

    // ── StorageInfo ──────────────────────────────────────────────────

    @Test
    fun `StorageInfo canFit should return true when enough space`() {
        val storage = DeviceCapabilities.StorageInfo(
            totalBytes = 50_000_000_000L,    // 50 GB
            availableBytes = 5_000_000_000L,  // 5 GB
            usedBytes = 45_000_000_000L
        )
        // Model size ~1.9 GB x 1.1 buffer ≈ 2.1 GB → fits in 5 GB
        assertTrue(storage.canFit(DeviceCapabilities.MODEL_SIZE_BYTES))
    }

    @Test
    fun `StorageInfo canFit should return false when not enough space`() {
        val storage = DeviceCapabilities.StorageInfo(
            totalBytes = 10_000_000_000L,
            availableBytes = 1_000_000_000L,  // 1 GB, but model needs ~2.1 GB with buffer
            usedBytes = 9_000_000_000L
        )
        assertFalse(storage.canFit(DeviceCapabilities.MODEL_SIZE_BYTES))
    }

    @Test
    fun `StorageInfo canFit should add 10 percent buffer`() {
        // Model is 1.9 GB, buffer adds 10% = 2.09 GB needed
        val storage = DeviceCapabilities.StorageInfo(
            totalBytes = 100_000_000_000L,
            availableBytes = (DeviceCapabilities.MODEL_SIZE_BYTES * 1.05).toLong(), // only 5% buffer
            usedBytes = 0L
        )
        // available is 1.995 GB, but needs 2.09 GB → false
        assertFalse(storage.canFit(DeviceCapabilities.MODEL_SIZE_BYTES))

        val storageOk = DeviceCapabilities.StorageInfo(
            totalBytes = 100_000_000_000L,
            availableBytes = (DeviceCapabilities.MODEL_SIZE_BYTES * 1.2).toLong(), // 20% buffer
            usedBytes = 0L
        )
        assertTrue(storageOk.canFit(DeviceCapabilities.MODEL_SIZE_BYTES))
    }

    @Test
    fun `StorageInfo GB conversions should be correct`() {
        val oneGb = 1_073_741_824L
        val storage = DeviceCapabilities.StorageInfo(
            totalBytes = oneGb * 64,
            availableBytes = oneGb * 10,
            usedBytes = oneGb * 54
        )
        assertEquals(64.0f, storage.totalGb, 0.5f)
        assertEquals(10.0f, storage.availableGb, 0.5f)
        assertEquals(54.0f, storage.usedGb, 0.5f)
    }

    // ── DeviceInfo structure ─────────────────────────────────────────

    @Test
    fun `DeviceInfo should compute ramGb initially`() {
        val info = DeviceCapabilities.DeviceInfo(
            ramGb = 4.0f,
            ramTier = DeviceCapabilities.RamTier.OK,
            gpu = null,
            cpu = null,
            storage = DeviceCapabilities.StorageInfo(0, 0, 0),
            isHighPerformanceDevice = false
        )
        assertEquals(4.0f, info.ramGb)
    }

    @Test
    fun `constants should match expected values`() {
        assertEquals(1_900_000_000L, DeviceCapabilities.MODEL_SIZE_BYTES)
        assertEquals(2.5f, DeviceCapabilities.RAM_BLOCK_GB)
        assertEquals(3.5f, DeviceCapabilities.RAM_WARN_GB)
    }
}
