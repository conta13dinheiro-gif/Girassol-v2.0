package com.enhikyy.girassol.research

import okhttp3.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

class ResearchPaused(message: String): IOException(message)
class HttpFailure(val status: Int,message: String): IOException(message)
fun interface ResearchGate { fun check() }
data class HttpDocument(val bytes: ByteArray,val etag: String?,val fetchedAt: Long)
interface MetadataCache { fun get(url: String): HttpDocument?; fun put(url: String,value: HttpDocument) }
class BoundedMetadataCache(private val capacity: Int=32): MetadataCache {
    private val values=object: LinkedHashMap<String,HttpDocument>(16,.75f,true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String,HttpDocument>?) = size>capacity
    }
    @Synchronized override fun get(url: String) = values[url]?.let { it.copy(bytes=it.bytes.copyOf()) }
    @Synchronized override fun put(url: String,value: HttpDocument) { values[url]=value.copy(bytes=value.bytes.copyOf()) }
}
/** Every body read/retry rechecks admission. Calls can also be cancelled by pressure/network callbacks. */
class ResilientHttp(private val cache: MetadataCache=BoundedMetadataCache(),private val gate: ResearchGate=ResearchGate {},
    private val client: OkHttpClient=OkHttpClient(),private val sleeper: (Long)->Unit=Thread::sleep,
    private val now: ()->Long=System::currentTimeMillis) {
    private val active=java.util.concurrent.ConcurrentHashMap.newKeySet<Call>()
    fun cancel() { active.forEach(Call::cancel) }
    fun get(url: HttpUrl,cacheable: Boolean=true,maxBytes: Int=2*1024*1024): HttpDocument {
        val key=url.toString(); val previous=if(cacheable) cache.get(key) else null
        var last: IOException?=null
        repeat(5) { attempt->
            gate.check()
            val request=Request.Builder().url(url).header("User-Agent","Girassol/0.7 Android research")
            previous?.etag?.let { request.header("If-None-Match",it) }
            val tuned=client.newBuilder().connectTimeout(10,TimeUnit.SECONDS).readTimeout(15L+attempt*5,TimeUnit.SECONDS)
                .callTimeout(30L+attempt*10,TimeUnit.SECONDS).build()
            val call=tuned.newCall(request.build()); active.add(call)
            try {
                call.execute().use { response->
                    gate.check()
                    if(response.code==304) return checkNotNull(previous) { "ETag returned without cached content" }
                    if(response.code==429 || (response.code in 500..599 && response.code!=501)) {
                        if(attempt==4) throw HttpFailure(response.code,"Hugging Face indisponível (${response.code})")
                        val wait=response.header("Retry-After")?.toLongOrNull()?.coerceIn(0,120)?.times(1000) ?: (1000L shl attempt)
                        // Sleep in small chunks so memory/network revocation interrupts backoff promptly.
                        var rest=wait
                        while(rest>0) { gate.check(); val slice=minOf(rest,100); sleeper(slice); rest-=slice }
                        return@repeat
                    }
                    if(!response.isSuccessful) throw HttpFailure(response.code,"Hugging Face respondeu ${response.code}")
                    val body=response.body ?: throw IOException("Resposta sem conteúdo")
                    if(body.contentLength()>maxBytes) throw IOException("Lote excede o orçamento de memória")
                    val bytes=body.byteStream().use { stream->
                        val out=ByteArrayOutputStream(); val buffer=ByteArray(8192)
                        while(true) {
                            gate.check(); val count=stream.read(buffer); if(count<0) break
                            if(out.size().toLong()+count>maxBytes) throw IOException("Lote excede o orçamento de memória")
                            out.write(buffer,0,count)
                        }
                        out.toByteArray()
                    }
                    val document=HttpDocument(bytes,response.header("ETag"),now())
                    if(cacheable) cache.put(key,document)
                    return document
                }
            } catch(e: ResearchPaused) { throw e }
            catch(e: HttpFailure) { throw e }
            catch(e: IOException) {
                gate.check(); last=e
                if(attempt==4) throw e
                var rest=1000L shl attempt
                while(rest>0) { gate.check(); val slice=minOf(rest,100); sleeper(slice); rest-=slice }
            } finally { active.remove(call) }
        }
        throw last ?: IOException("Pesquisa não concluída")
    }
}
