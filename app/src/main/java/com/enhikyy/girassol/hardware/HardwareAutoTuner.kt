package com.enhikyy.girassol.hardware

import android.app.ActivityManager
import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES30
import android.os.Build
import java.io.File

/** Read-only hardware inventory; library presence is not a driver capability test. */
data class DeviceHardwareReport(
    val manufacturer: String,
    val model: String,
    val socVendor: String,
    val socModel: String,
    val architecture: String,
    val primaryAbi: String,
    val supportedAbis: List<String>,
    val gpuRenderer: String,
    val totalRamGb: Float,
    val logicalCpuCount: Int,
    val openClLibraryFileFound: Boolean,
    val vulkanLibraryFileFound: Boolean,
    val nativeProbeAvailable: Boolean,
    val hasNeon: Boolean,
    val hasDotProd: Boolean,
    val hasI8mm: Boolean,
    val hasSve2: Boolean,
    val hasAvx2: Boolean,
    val bigCoreIds: List<Int>,
    val littleCoreIds: List<Int>,
    val ggmlVariantOrder: String
)

internal data class NativeHardwareSnapshot(
    val abi: String, val neon: Boolean, val dotprod: Boolean, val i8mm: Boolean,
    val sve2: Boolean, val avx2: Boolean, val openClLoader: Boolean, val vulkanLoader: Boolean,
    val logicalCpus: Int, val bigCores: List<Int>, val littleCores: List<Int>, val ggmlVariants: String
) {
    companion object {
        fun parse(raw: String): NativeHardwareSnapshot {
            val values = raw.lineSequence().filter { it.isNotBlank() }.associate { line ->
                val separator = line.indexOf('=')
                require(separator > 0) { "Malformed native hardware field" }
                line.substring(0, separator) to line.substring(separator + 1)
            }
            fun flag(key: String) = when(values[key]) {
                "true" -> true
                "false" -> false
                else -> error("Missing or invalid native hardware boolean: $key")
            }
            fun cores(key: String) = values[key].orEmpty().split(',').filter { it.isNotBlank() }
                .map { it.toInt().also { cpu -> require(cpu in 0 until 4096) } }.distinct()
            val logical = values["logical_cpus"]?.toIntOrNull() ?: error("Missing CPU count")
            require(logical > 0)
            val big = cores("big_cores"); val little = cores("little_cores")
            require((big + little).distinct().size == big.size + little.size) { "Duplicate CPU cluster assignment" }
            return NativeHardwareSnapshot(
                values["abi"]?.takeIf { it.isNotBlank() } ?: error("Missing ABI"),
                flag("neon"), flag("dotprod"), flag("i8mm"), flag("sve2"), flag("avx2"),
                flag("opencl_loader"), flag("vulkan_loader"), logical, big, little,
                values["ggml_variants"]?.takeIf { it.isNotBlank() } ?: error("Missing variant order")
            )
        }
    }
}

object HardwareAutoTuner {
    fun probe(context: Context): DeviceHardwareReport {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memory = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
        val abis = Build.SUPPORTED_ABIS.toList()
        val architecture = System.getProperty("os.arch") ?: "unknown"
        val socManufacturer = Build.SOC_MANUFACTURER.takeUnless { it.isBlank() } ?: Build.MANUFACTURER
        val socModel = Build.SOC_MODEL.takeUnless { it.isBlank() } ?: Build.HARDWARE
        val identity = listOf(
            Build.MANUFACTURER, Build.HARDWARE, Build.BOARD, socManufacturer, socModel
        ).joinToString(" ").lowercase()
        val renderer = queryGpuRenderer()
        val fingerprint = "$identity $renderer".lowercase()
        val native = if (com.enhikyy.girassol.cognition.GeaNativeBridge.available) {
            runCatching {
                NativeHardwareSnapshot.parse(com.enhikyy.girassol.cognition.GeaNativeBridge.nativeHardwareProbe())
            }.getOrNull()
        } else null
        val vendor = when {
            architecture.contains("x86", ignoreCase = true) || abis.any { it.contains("x86") } -> "x86_64 / Intel-AMD"
            fingerprint.contains("qualcomm") || fingerprint.contains("qcom") || fingerprint.contains("adreno") -> "Qualcomm Snapdragon"
            fingerprint.contains("exynos") || fingerprint.contains("samsung") || fingerprint.contains("xclipse") || fingerprint.contains("s5e") -> "Samsung Exynos"
            fingerprint.contains("mediatek") || fingerprint.contains("mt6") || fingerprint.contains("mt8") ||
                fingerprint.contains("dimensity") || fingerprint.contains("helio") -> "MediaTek"
            fingerprint.contains("tensor") || fingerprint.contains("gs1") || fingerprint.contains("gs2") || fingerprint.contains("gs3") -> "Google Tensor"
            else -> "ARM genérico / não identificado"
        }
        return DeviceHardwareReport(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            socVendor = vendor,
            socModel = socModel,
            architecture = architecture,
            primaryAbi = abis.firstOrNull() ?: "unknown",
            supportedAbis = abis,
            gpuRenderer = renderer,
            totalRamGb = memory.totalMem / (1024f * 1024f * 1024f),
            logicalCpuCount = native?.logicalCpus ?: Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
            openClLibraryFileFound = native?.openClLoader ?: anyFile(
                "/vendor/lib64/libOpenCL.so",
                "/system/vendor/lib64/libOpenCL.so",
                "/system/lib64/libOpenCL.so",
                "/vendor/lib/libOpenCL.so",
                "/system/lib/libOpenCL.so"
            ),
            vulkanLibraryFileFound = native?.vulkanLoader ?: anyFile(
                "/system/lib64/libvulkan.so",
                "/vendor/lib64/libvulkan.so",
                "/system/lib/libvulkan.so",
                "/vendor/lib/libvulkan.so"
            ),
            nativeProbeAvailable = native != null,
            hasNeon = native?.neon ?: false,
            hasDotProd = native?.dotprod ?: false,
            hasI8mm = native?.i8mm ?: false,
            hasSve2 = native?.sve2 ?: false,
            hasAvx2 = native?.avx2 ?: false,
            bigCoreIds = native?.bigCores.orEmpty(),
            littleCoreIds = native?.littleCores.orEmpty(),
            ggmlVariantOrder = native?.ggmlVariants ?: "portable / fallback"
        )
    }

    private fun anyFile(vararg paths: String): Boolean =
        paths.any { runCatching { File(it).isFile }.getOrDefault(false) }

    private fun queryGpuRenderer(): String {
        var display = EGL14.EGL_NO_DISPLAY
        var context: android.opengl.EGLContext? = null
        var surface: android.opengl.EGLSurface? = null
        var initialized = false
        return try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) return "EGL indisponível"
            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) return "EGL não inicializado"
            initialized = true
            val attributes = intArrayOf(EGL14.EGL_RENDERABLE_TYPE, 0x0040, EGL14.EGL_NONE)
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) || count[0] == 0) {
                return "Configuração GLES 3 indisponível"
            }
            val config = configs[0] ?: return "Configuração EGL indisponível"
            context = EGL14.eglCreateContext(
                display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0
            )
            if (context == null || context == EGL14.EGL_NO_CONTEXT) return "Contexto GLES 3 indisponível"
            surface = EGL14.eglCreatePbufferSurface(
                display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0
            )
            if (surface == null || surface == EGL14.EGL_NO_SURFACE) return "Superfície EGL indisponível"
            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) return "Contexto EGL não ativado"
            GLES30.glGetString(GLES30.GL_RENDERER)?.takeIf { it.isNotBlank() } ?: "GPU não identificada"
        } catch (_: Throwable) {
            "GPU indisponível para consulta EGL"
        } finally {
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(
                    display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
                )
                surface?.let { EGL14.eglDestroySurface(display, it) }
                context?.let { EGL14.eglDestroyContext(display, it) }
                if (initialized) EGL14.eglTerminate(display)
            }
        }
    }
}
