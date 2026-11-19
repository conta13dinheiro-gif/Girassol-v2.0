package com.enhikyy.girassol.cognition

internal object NativeCognition {
    init { System.loadLibrary("girassol_llama") }
    private external fun nativeInfer(path: String, prompt: ByteArray, request: Long, adapter: String?, review: Boolean): ByteArray
    fun infer(path: String,prompt: ByteArray,request: Long,adapter: String?=null): ByteArray = nativeInfer(path,prompt,request,adapter,false)
    fun review(path: String,prompt: ByteArray,request: Long,adapter: String?=null): ByteArray = nativeInfer(path,prompt,request,adapter,true)
    external fun cancel(request: Long)
}
