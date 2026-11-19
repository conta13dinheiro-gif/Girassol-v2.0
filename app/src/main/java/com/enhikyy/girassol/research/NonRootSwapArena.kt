package com.enhikyy.girassol.research

/** JNI mappings are scoped to decode; no ByteBuffer can survive munmap or share a dangling pointer. */
internal object NonRootSwapArena {
    init { System.loadLibrary("girassol_projection") }
    private val requests=java.util.concurrent.atomic.AtomicLong()
    private external fun nativeDecode(path: String,format: String,offset: Long,request: Long): ByteArray
    private external fun cancel(request: Long)
    fun cancelAll() { cancel(requests.get()) }
    fun decode(path: String,format: String,offset: Long): ByteArray {
        if(Thread.currentThread().isInterrupted) throw ResearchPaused("Decodificação interrompida")
        return nativeDecode(path,format,offset,requests.incrementAndGet())
    }
}
