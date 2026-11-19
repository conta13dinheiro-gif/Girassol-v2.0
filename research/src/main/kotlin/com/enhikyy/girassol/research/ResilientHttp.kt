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
    /** Disk-only transfer. A persisted ETag + If-Range prevents stitching different revisions. */
    fun download(url: HttpUrl,target: java.io.File,maxBytes: Long=150L*1024*1024): String {
        require(maxBytes in 1..150L*1024*1024)
        target.parentFile!!.mkdirs()
        require(!target.isFile || target.length()<=maxBytes)
        if(target.isFile) return target.inputStream().use { input->
            val digest=java.security.MessageDigest.getInstance("SHA-256");val bytes=ByteArray(8192)
            while(true) { gate.check();val n=input.read(bytes);if(n<0) break;digest.update(bytes,0,n) }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
        val part=java.io.File(target.path+".part");val metadata=java.io.File(target.path+".etag")
        var last: IOException?=null
        repeat(5) { attempt->
            gate.check()
            val offset=part.takeIf { it.isFile }?.length() ?: 0L
            require(offset<=maxBytes)
            val etag=metadata.takeIf { it.isFile && it.length()<=1024 }?.readText()
            val request=Request.Builder().url(url).header("Accept-Encoding","identity")
            if(offset>0) { request.header("Range","bytes=$offset-");etag?.let { request.header("If-Range",it) } }
            val call=client.newBuilder().readTimeout(30L+attempt*10,TimeUnit.SECONDS).callTimeout(300,TimeUnit.SECONDS).build().newCall(request.build())
            active.add(call)
            try {
                call.execute().use { response->
                    gate.check()
                    if(response.code==429 || response.code in 500..599) throw IOException("Download temporariamente indisponível: ${response.code}")
                    if(response.code!=200 && response.code!=206) throw HttpFailure(response.code,"Download recusado: ${response.code}")
                    val append=response.code==206
                    val body=response.body ?: throw IOException("Download sem conteúdo")
                    val total=if(append) {
                        val range=Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)").matchEntire(response.header("Content-Range") ?: "") ?: throw IOException("Content-Range inválido")
                        val start=range.groupValues[1].toLong();val end=range.groupValues[2].toLong();val length=range.groupValues[3].toLong()
                        if(start!=offset || end<start || end>=length || (etag!=null && response.header("ETag")!=etag)) throw IOException("Revisão/intervalo mudou durante retomada")
                        length
                    } else body.contentLength()
                    if(total<0 || total>maxBytes) throw ResearchPaused("Tamanho direto desconhecido ou acima de150 MB; use streaming")
                    response.header("ETag")?.let { metadata.writeText(it) }
                    java.io.FileOutputStream(part,append).use { output->
                        body.byteStream().use { input->
                            val buffer=ByteArray(8192);var length=if(append) offset else 0L
                            while(true) {
                                gate.check();val count=input.read(buffer);if(count<0) break
                                length+=count
                                if(length>maxBytes || length>total) throw IOException("Download excede tamanho anunciado")
                                if(target.parentFile!!.usableSpace<64L*1024*1024+count) throw ResearchPaused("Espaço de disco insuficiente")
                                output.write(buffer,0,count)
                            }
                            if(length!=total) throw IOException("Download parcial; retomada preservada")
                        }
                        output.fd.sync()
                    }
                    java.nio.file.Files.move(part.toPath(),target.toPath(),java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    metadata.delete()
                    return download(url,target,maxBytes)
                }
            } catch(e: ResearchPaused) { throw e } catch(e: HttpFailure) { throw e } catch(e: IOException) {
                last=e;if(attempt==4) throw e
                var rest=1000L shl attempt
                while(rest>0) { gate.check();val slice=minOf(rest,100);sleeper(slice);rest-=slice }
            } finally { active.remove(call) }
        }
        throw last ?: IOException("Download não concluído")
    }
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
