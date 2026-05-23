package com.pocketfinancer.hardware

import android.app.ActivityManager
import android.content.Context
import android.opengl.GLES20
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLContext

/**
 * Device capability detection.
 *
 * Ported from the React Native HardwareInfoModule.kt — RN bridge removed.
 * Checks RAM, GPU (Adreno/Mali/PowerVR), CPU features (i8mm, dotprod, fp16).
 */
@Singleton
class DeviceCapabilities @Inject constructor(
    @ApplicationContext private val context: Context
) {
    data class GpuInfo(
        val renderer: String,
        val vendor: String,
        val version: String,
        val hasAdreno: Boolean,
        val hasMali: Boolean,
        val hasPowerVR: Boolean,
        val gpuType: String
    )

    data class CpuInfo(
        val cores: Int,
        val features: Set<String>,
        val hasI8mm: Boolean,
        val hasDotProd: Boolean,
        val hasFp16: Boolean,
        val socModel: String?
    )

    data class StorageInfo(
        val totalBytes: Long,
        val availableBytes: Long,
        val usedBytes: Long
    ) {
        val totalGb: Float get() = totalBytes / (1024f * 1024f * 1024f)
        val availableGb: Float get() = availableBytes / (1024f * 1024f * 1024f)
        val usedGb: Float get() = usedBytes / (1024f * 1024f * 1024f)

        /**
         * Check if there's enough space for a file of [requiredBytes] size.
         * Adds a 10% buffer on top of the requirement for safety.
         */
        fun canFit(requiredBytes: Long): Boolean {
            val withBuffer = (requiredBytes * 1.1).toLong()
            return availableBytes >= withBuffer
        }
    }

    /** RAM capability tiers for the 1.7B Q8_0 model. */
    enum class RamTier {
        /** < 3.5GB — cannot run the model. App is blocked. */
        BLOCKED,
        /** 3.5 – 4.0GB — can run but device will struggle. Show warning banner. */
        WARNING,
        /** > 4.0GB — sufficient headroom for model + OS + UI. */
        OK
    }

    data class DeviceInfo(
        val ramGb: Float,
        val totalRamGb: Float,
        val ramTier: RamTier,
        val gpu: GpuInfo?,
        val cpu: CpuInfo?,
        val storage: StorageInfo,
        val isHighPerformanceDevice: Boolean
    )

    companion object {
        /** Qwen3-1.7B Q8_0 approximate GGUF size in bytes (~1.8GB). */
        const val MODEL_SIZE_BYTES: Long = 1_900_000_000L

        /** Below this RAM (GB), the app is blocked entirely. */
        const val RAM_BLOCK_GB: Float = 2.5f

        /** Below this RAM (GB) but above BLOCK, show a warning banner. */
        const val RAM_WARN_GB: Float = 3.5f

        private const val TAG = "DeviceCapabilities"
    }

    /**
     * Query total RAM on the device in GB.
     */
    fun checkRamGb(): Float {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(memInfo)
        return memInfo.totalMem / (1024f * 1024f * 1024f)
    }

    /**
     * Get GPU information via EGL / OpenGL.
     * Returns null if GPU info cannot be queried.
     */
    fun getGpuInfo(): GpuInfo? {
        try {
            var renderer = ""
            var vendor = ""
            var version = ""

            val egl = EGLContext.getEGL() as EGL10
            val display = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY)
            if (display == EGL10.EGL_NO_DISPLAY) return null

            val versionArray = IntArray(2)
            if (!egl.eglInitialize(display, versionArray)) return null

            val configsCount = IntArray(1)
            val configs = arrayOfNulls<EGLConfig>(1)
            val configSpec = intArrayOf(EGL10.EGL_RENDERABLE_TYPE, 4, EGL10.EGL_NONE)
            egl.eglChooseConfig(display, configSpec, configs, 1, configsCount)

            if (configsCount[0] > 0) {
                val context = egl.eglCreateContext(
                    display, configs[0], EGL10.EGL_NO_CONTEXT,
                    intArrayOf(0x3098, 2, EGL10.EGL_NONE)
                )
                if (context != null && context != EGL10.EGL_NO_CONTEXT) {
                    val surfaceAttribs = intArrayOf(EGL10.EGL_WIDTH, 1, EGL10.EGL_HEIGHT, 1, EGL10.EGL_NONE)
                    val surface = egl.eglCreatePbufferSurface(display, configs[0], surfaceAttribs)
                    if (surface != null && surface != EGL10.EGL_NO_SURFACE) {
                        egl.eglMakeCurrent(display, surface, surface, context)
                        renderer = GLES20.glGetString(GLES20.GL_RENDERER) ?: ""
                        vendor = GLES20.glGetString(GLES20.GL_VENDOR) ?: ""
                        version = GLES20.glGetString(GLES20.GL_VERSION) ?: ""
                        egl.eglMakeCurrent(display, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT)
                        egl.eglDestroySurface(display, surface)
                    }
                    egl.eglDestroyContext(display, context)
                }
            }
            egl.eglTerminate(display)

            val rendererLower = renderer.lowercase()
            val hasAdreno = rendererLower.contains("adreno") || rendererLower.contains("qcom") || rendererLower.contains("qualcomm")
            val hasMali = rendererLower.contains("mali")
            val hasPowerVR = rendererLower.contains("powervr")

            val gpuType = when {
                hasAdreno -> "Adreno (Qualcomm)"
                hasMali -> "Mali (ARM)"
                hasPowerVR -> "PowerVR (Imagination)"
                renderer.isNotEmpty() -> renderer
                else -> "Unknown"
            }

            return GpuInfo(renderer, vendor, version, hasAdreno, hasMali, hasPowerVR, gpuType)
        } catch (_: Exception) {
            return null
        }
    }

    /**
     * Parse /proc/cpuinfo for CPU features and core count.
     */
    fun getCpuInfo(): CpuInfo? {
        try {
            val cores = Runtime.getRuntime().availableProcessors()
            val features = mutableSetOf<String>()
            val cpuInfoFile = File("/proc/cpuinfo")

            if (cpuInfoFile.exists()) {
                for (line in cpuInfoFile.readLines()) {
                    val parts = line.split(":")
                    if (parts.size >= 2) {
                        val key = parts[0].trim()
                        when (key) {
                            "flags", "Features" -> {
                                features.addAll(parts[1].trim().split(" ").filter { it.isNotEmpty() })
                            }
                        }
                    }
                }
            }

            val socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else null

            return CpuInfo(
                cores = cores,
                features = features,
                hasI8mm = features.any { it == "i8mm" },
                hasDotProd = features.any { it in setOf("dotprod", "asimddp") },
                hasFp16 = features.any { it in setOf("fphp", "fp16") },
                socModel = socModel
            )
        } catch (_: Exception) {
            return null
        }
    }

    /**
     * Determine if this device can handle the larger 8-bit model tiers.
     *
     * Based on CPU features (i8mm + dotprod) — ARM v8.2-A/v8.6-A instructions
     * that accelerate matrix multiplication for LLM inference. Devices with
     * these features run CPU inference of larger models significantly faster.
     *
     * GPU acceleration is NOT checked here because:
     * - llama.cpp Vulkan/OpenCL backends are 10–15× slower than CPU on Android
     *   across ALL GPU vendors (Adreno, Mali, PowerVR) — see ggml-org/#9464.
     * - The app currently builds llama.cpp CPU-only (CMakeLists sets all GPU
     *   backends OFF) and calls loadModel() with gpuLayers=0.
     * - Callers in SettingsViewModel therefore always pass gpuLayers=0.
     *
     * Returns false if CPU info cannot be read (emulators, headless envs).
     */
    fun isHighPerformanceDevice(): Boolean {
        val cpu = getCpuInfo() ?: return false
        return cpu.hasI8mm && cpu.hasDotProd
    }

    /**
     * Check available storage on the device's primary data partition.
     *
     * Uses StatFs on the internal files directory path. Returns total,
     * available, and used bytes. Call [StorageInfo.canFit] with the model
     * size before downloading the GGUF.
     */
    fun checkStorage(): StorageInfo {
        val path = context.filesDir.absolutePath
        val stat = StatFs(path)
        val blockSize = stat.blockSizeLong
        val totalBlocks = stat.blockCountLong
        val availableBlocks = stat.availableBlocksLong
        val totalBytes = totalBlocks * blockSize
        val availableBytes = availableBlocks * blockSize
        val usedBytes = totalBytes - availableBytes

        Log.d(TAG, "Storage: total=${"%.1f".format(totalBytes / 1e9)}GB, " +
                "available=${"%.1f".format(availableBytes / 1e9)}GB")

        return StorageInfo(totalBytes, availableBytes, usedBytes)
    }

    /**
     * Determine the RAM capability tier for the device.
     *
     * - BLOCKED (< 3.5GB): the 1.7B Q8_0 model cannot fit in RAM alongside
     *   the OS. App should show an interstitial and refuse to proceed.
     * - WARNING (3.5 – 4.0GB): the model fits but headroom is tight. The
     *   device may kill background apps during inference. Show a caution
     *   banner but allow the user to continue.
     * - OK (> 4.0GB): enough RAM for model + OS + UI.
     */
    fun ramTierFor(ramGb: Float): RamTier {
        return when {
            ramGb < RAM_BLOCK_GB -> RamTier.BLOCKED
            ramGb < RAM_WARN_GB  -> RamTier.WARNING
            else                 -> RamTier.OK
        }
    }

    /**
     * Full device capability check. Returns all info in one call.
     */
    fun assessDevice(): DeviceInfo {
        val ram = checkRamGb()
        val tier = ramTierFor(ram)
        val storage = checkStorage()
        val gpu = try { getGpuInfo() } catch (_: Exception) { null }
        val cpu = try { getCpuInfo() } catch (_: Exception) { null }
        val highPerf = isHighPerformanceDevice()

        return DeviceInfo(ram, ram, tier, gpu, cpu, storage, highPerf)
    }
}
