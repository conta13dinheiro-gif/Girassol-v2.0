package com.enhikyy.girassol.cognition

internal object NativeCognition {
    init { System.loadLibrary("girassol_llama") }
    private external fun nativeInfer(path: String, prompt: ByteArray, request: Long, adapter: String?, mode: Int): ByteArray
    fun infer(path: String,prompt: ByteArray,request: Long,adapter: String?=null): ByteArray = nativeInfer(path,prompt,request,adapter,0)
    fun review(path: String,prompt: ByteArray,request: Long,adapter: String?=null): ByteArray = nativeInfer(path,prompt,request,adapter,1)
    fun embedding(path: String,text: String,request: Long,adapter: String?=null): List<Float> {
        val bytes=nativeInfer(path,text.toByteArray(),request,adapter,2)
        require(bytes.isNotEmpty() && bytes.size%4==0)
        val data=java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.nativeOrder())
        return List(bytes.size/4) { data.float }
    }
    external fun cancel(request: Long)
}
