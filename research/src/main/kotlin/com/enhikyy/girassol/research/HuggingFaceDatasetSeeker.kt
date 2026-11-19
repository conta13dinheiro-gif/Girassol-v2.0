package com.enhikyy.girassol.research

import kotlinx.serialization.json.*
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import kotlin.math.ln

/** Public Hub and Dataset Viewer only; datasets are data and never executed as code. */
class HuggingFaceDatasetSeeker(private val http: ResilientHttp,
    private val hub: HttpUrl="https://huggingface.co/".toHttpUrl(),
    private val viewer: HttpUrl="https://datasets-server.huggingface.co/".toHttpUrl()) {
    private val json=Json { ignoreUnknownKeys=true }
    private val allowedLicenses=setOf("mit","apache-2.0","cc0-1.0","cc-by-4.0","cc-by-sa-4.0","bsd-3-clause","bsd-2-clause")
    private val preferredTasks=setOf("text-generation","question-answering","summarization","text2text-generation")
    private fun url(base: HttpUrl,path: List<String>,parameters: Map<String,String> = emptyMap()): HttpUrl = base.newBuilder().apply {
        path.forEach(::addPathSegment); parameters.forEach { (k,v)->addQueryParameter(k,v) }
    }.build()
    private fun parse(document: HttpDocument) = json.parseToJsonElement(document.bytes.decodeToString())
    private fun repoParts(id: String): List<String> {
        require(id.matches(Regex("[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)?")) && id.split('/').none { it=="." || it==".." })
        return id.split('/')
    }
    fun search(intent: DatasetResearchIntent): List<String> {
        fun query(term: String): List<String> = parse(http.get(url(hub,listOf("api","datasets"),mapOf("search" to term,"sort" to "downloads","direction" to "-1","limit" to "20"))))
            .jsonArray.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }
        val precise=listOf(intent.queryEn,intent.queryPt).flatMap(::query).distinct()
        return if(precise.isNotEmpty()) precise else listOf(intent.queryEn.substringBefore(' '),intent.queryPt.substringBefore(' ')).distinct().flatMap(::query).distinct()
    }
    fun inspect(id: String): DatasetRepo? {
        val obj=parse(http.get(url(hub,listOf("api","datasets")+repoParts(id)))).jsonObject
        if(obj["private"]?.jsonPrimitive?.booleanOrNull==true || obj["disabled"]?.jsonPrimitive?.booleanOrNull==true) return null
        val gated=obj["gated"]?.jsonPrimitive?.contentOrNull
        if(gated!=null && gated!="false") return null
        val card=obj["cardData"] as? JsonObject ?: JsonObject(emptyMap())
        val tags=(obj["tags"] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull }
        fun values(e: JsonElement?): List<String> = when(e) { is JsonArray->e.mapNotNull { it.jsonPrimitive.contentOrNull }; is JsonPrimitive->listOfNotNull(e.contentOrNull);else->emptyList() }
        val licenses=values(card["license"])+tags.filter { it.startsWith("license:") }.map { it.removePrefix("license:") }
        val license=licenses.firstOrNull { it in allowedLicenses } ?: return null
        val tasks=(values(card["task_categories"])+tags.filter { it.startsWith("task_categories:") }.map { it.removePrefix("task_categories:") }).distinct()
        val media=tags.any { it in setOf("video","audio","image","modality:video","modality:audio","modality:image") }
        val text=tags.any { it in setOf("text","modality:text") } || tasks.any { it in preferredTasks }
        if(media && !text) return null
        if(!text && tasks.none { it in preferredTasks }) return null
        val downloads=obj["downloads"]?.jsonPrimitive?.longOrNull?.coerceAtLeast(0) ?: 0
        return DatasetRepo(id,obj["sha"]?.jsonPrimitive?.contentOrNull ?: "",downloads,license,tasks,
            tasks.count { it in preferredTasks }*2.0+ln(downloads+1.0)/10)
    }
    fun splits(id: String): List<DatasetSplit> {
        repoParts(id)
        val obj=parse(http.get(url(viewer,listOf("splits"),mapOf("dataset" to id)))).jsonObject
        return (obj["splits"] as? JsonArray).orEmpty().mapNotNull { e->
            val row=e.jsonObject; val config=row["config"]?.jsonPrimitive?.contentOrNull; val split=row["split"]?.jsonPrimitive?.contentOrNull
            if(config.isNullOrBlank() || split.isNullOrBlank()) null else DatasetSplit(config,split)
        }.distinct().sortedBy { if(it.split=="train") 0 else 1 }
    }
    fun acquire(repo: DatasetRepo,subset: DatasetSplit,intent: DatasetResearchIntent,offset: Long=0): DatasetBatch {
        require(offset>=0); repoParts(repo.id)
        val common=mapOf("dataset" to repo.id,"config" to subset.config,"split" to subset.split,"offset" to offset.toString(),"length" to "100")
        var endpoint=url(viewer,listOf("search"),common+mapOf("query" to intent.queryEn))
        val document=try { http.get(endpoint,false) } catch(e: HttpFailure) {
            if(e.status !in setOf(404,422,501)) throw e
            endpoint=url(viewer,listOf("rows"),common); http.get(endpoint,false)
        }
        val rows=DatasetText.rows(document.bytes,offset)
        if(rows.isEmpty()) throw IOException("Nenhum trecho textual completo encontrado neste lote")
        return DatasetBatch(repo,subset,intent.queryHash,endpoint.toString(),document.fetchedAt,sha256(document.bytes),document.bytes,rows)
    }
    fun discover(intent: DatasetResearchIntent,installed: (DatasetKey)->Boolean): Pair<DatasetRepo,DatasetSplit>? = search(intent)
        .mapNotNull { id->
            try { inspect(id) } catch(e: ResearchPaused) { throw e } catch(_: HttpFailure) { null }
        }.sortedByDescending { it.score }.firstNotNullOfOrNull { repo->
            splits(repo.id).firstOrNull { !installed(DatasetKey(repo.id,it.config,it.split,intent.queryHash)) }?.let { repo to it }
        }
}

object DatasetText {
    fun rows(raw: ByteArray,offset: Long=0): List<DatasetRow> {
        val body=Json.parseToJsonElement(raw.decodeToString()).jsonObject
        return (body["rows"] as? JsonArray).orEmpty().mapIndexedNotNull { position,element->
            val wrapper=element.jsonObject
            if((wrapper["truncated_cells"] as? JsonArray)?.isNotEmpty()==true) return@mapIndexedNotNull null
            val row=wrapper["row"] as? JsonObject ?: return@mapIndexedNotNull null
            val preferred=setOf("text","question","answer","context","explanation","instruction","output","content","title","response","correct_answer","solution")
            val fields=row.entries.filter { it.key.lowercase() in preferred && it.value is JsonPrimitive && (it.value as JsonPrimitive).isString }
            val text=fields.joinToString("\n") { "${it.key}: ${it.value.jsonPrimitive.content}" }.trim()
            if(text.length<16 || text.length>32768 || text.indexOf('\u0000')>=0) return@mapIndexedNotNull null
            DatasetRow(wrapper["row_idx"]?.jsonPrimitive?.longOrNull ?: (offset+position),text,sha256(text.toByteArray()))
        }
    }
}
