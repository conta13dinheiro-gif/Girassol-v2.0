package com.enhikyy.girassol.research

import android.content.Context
import com.enhikyy.girassol.data.WorldStore
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File

/** Compact textual fallback. Raw files are an evictable cache; selected evidence stays in WAL. */
internal class CompactDatasetSource(context: Context,private val http: ResilientHttp,private val database: ()->String,private val settings: ()->ResearchSettings) {
    private val app=context.applicationContext
    private val directory=File(app.noBackupFilesDir,"dataset-cache").apply { mkdirs() }
    fun bytes(): Long = directory.listFiles().orEmpty().filter { it.isFile }.sumOf { it.length() }
    fun acquire(repo: DatasetRepo,subset: DatasetSplit,intent: DatasetResearchIntent,offset: Long): DatasetBatch {
        require(repo.revision.matches(Regex("[a-fA-F0-9]{7,64}"))) { "Download direto requer revisão identificável" }
        val base="https://huggingface.co/".toHttpUrl()
        val tree=base.newBuilder().addPathSegment("api").addPathSegment("datasets").apply { repo.id.split('/').forEach(::addPathSegment) }
            .addPathSegment("tree").addPathSegment(repo.revision).addQueryParameter("recursive","true").build()
        val files=ResearchCodec.json.parseToJsonElement(http.get(tree).bytes.decodeToString()).jsonArray
        val candidate=files.mapNotNull { item->
            val row=item.jsonObject;val path=row["path"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val bytes=row["size"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            if(bytes !in 1..150L*1024*1024 || path.substringAfterLast('.').lowercase() !in setOf("jsonl","json","csv") || path.split('/').any { it==".." || it=="." }) null else path to bytes
        }.sortedBy { it.second }.firstOrNull() ?: throw ResearchPaused("Nenhum arquivo textual compacto disponível; aguarde o Datasets Server")
        val url=base.newBuilder().apply { repo.id.split('/').forEach(::addPathSegment) }.addPathSegment("resolve").addPathSegment(repo.revision).apply { candidate.first.split('/').forEach(::addPathSegment) }.build()
        val target=File(directory,sha256(url.toString().toByteArray())+"."+candidate.first.substringAfterLast('.').lowercase())
        val controls=WorldStore(app,database())
        val immutable=try { controls.research().bytes() } finally { controls.close() }
        val entries=directory.listFiles().orEmpty().filter { it.isFile && !it.name.endsWith(".etag") && !it.name.endsWith(".intent") }.map { file->
            val owner=File(directory,file.name.removeSuffix(".part")+".intent").takeIf { it.isFile && it.length()==64L }?.readText()
            val pinned=if(owner==null) false else WorldStore(app,database()).use { it.research().entry(owner)?.pinned==true }
            CacheEntry(file.name,file.length(),file.lastModified(),pinned || file.name==target.name || file.name==target.name+".part")
        }
        val incoming=if(target.isFile) 0 else (candidate.second-File(target.path+".part").length()).coerceAtLeast(0)
        val victims=QuotaLru.victims(entries,immutable,incoming,settings().quotaMb*1048576L)
        victims.forEach { name->File(directory,name).delete();File(directory,name+".etag").delete();File(directory,name.removeSuffix(".part")+".intent").delete() }
        File(directory,target.name+".intent").writeText(intent.id)
        val sourceHash=http.download(url,target,candidate.second)
        target.setLastModified(System.currentTimeMillis())
        val extracted=NonRootSwapArena.decode(target.absolutePath,target.extension,offset)
        val document=ResearchCodec.json.parseToJsonElement(extracted.decodeToString()).jsonObject
        // The exact selected bytes AND the hash of the complete source file are sealed in the journal.
        val body=JsonObject(document+mapOf("source_sha256" to JsonPrimitive(sourceHash),"source_file" to JsonPrimitive(candidate.first),"source_revision" to JsonPrimitive(repo.revision),"requested_config" to JsonPrimitive(subset.config),"requested_split" to JsonPrimitive(subset.split))).toString().toByteArray()
        val rows=DatasetText.rows(body,offset)
        check(rows.isNotEmpty()) { "Arquivo compacto sem registros textuais completos nesta janela" }
        return DatasetBatch(repo,DatasetSplit("file:${candidate.first}","all"),intent.queryHash,url.toString(),System.currentTimeMillis(),sha256(body),body,rows)
    }
}
