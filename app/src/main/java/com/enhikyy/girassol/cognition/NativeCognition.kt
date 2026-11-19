package com.enhikyy.girassol.cognition

internal object NativeCognition {
    init { System.loadLibrary("girassol_llama") }
    private external fun nativeInfer(path: String, prompt: ByteArray, request: Long, adapter: String?): ByteArray
    fun infer(path: String,prompt: ByteArray,request: Long,adapter: String?=null): ByteArray = nativeInfer(path,prompt,request,adapter)
    external fun cancel(request: Long)
}
