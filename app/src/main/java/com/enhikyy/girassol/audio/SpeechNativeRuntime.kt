package com.enhikyy.girassol.audio

/**
 * Loads the Sherpa-ONNX runtime already packaged in the ARM64 APK.
 *
 * Keep dependency order explicit: ONNX Runtime, C API, C++ API, then JNI.
 * The APK carries these libraries, so first-use speech startup has no network
 * provisioning step and never loads native code from writable app storage.
 */
internal object SpeechNativeRuntime {
    private val libraries = listOf(
        "onnxruntime",
        "sherpa-onnx-c-api",
        "sherpa-onnx-cxx-api",
        "sherpa-onnx-jni"
    )

    @Volatile
    private var loaded = false

    @Synchronized
    fun ensureLoaded() {
        if (loaded) return
        check(android.os.Process.is64Bit()) {
            "O runtime neural embarcado requer processo Android de 64 bits"
        }
        libraries.forEach(System::loadLibrary)
        loaded = true
    }
}
