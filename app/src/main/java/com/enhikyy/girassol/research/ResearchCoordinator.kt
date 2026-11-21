package com.enhikyy.girassol.research

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.text.Html
import android.os.Handler
import android.os.Process
import com.enhikyy.girassol.core.*
import com.enhikyy.girassol.data.WorldStore
import com.enhikyy.girassol.cognition.*
import com.enhikyy.girassol.plugins.PluginRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import java.io.File
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.parquet.example.data.Group
import org.apache.parquet.hadoop.ParquetReader
import org.apache.parquet.hadoop.example.GroupReadSupport
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.atomic.AtomicLong
import okhttp3.HttpUrl.Companion.toHttpUrl

internal data class ResearchStatus(val settings: ResearchSettings=ResearchSettings(),val entries: List<ResearchEntry> = emptyList(),
    val message: String="Ative a pesquisa para os habitantes consultarem o Hugging Face.",val bytes: Long=0,val busy: Boolean=false)
internal object ResearchHub { val mutable=MutableStateFlow(ResearchStatus());val state=mutable.asStateFlow() }

/** Async effects return to the sole world writer. A generation token prevents cross-timeline writes. */
internal class ResearchCoordinator(context: Context,private val handler: Handler,private val store: ()->WorldStore) : AutoCloseable {
    private val app=context.applicationContext
    private val prefs=app.getSharedPreferences("research",Context.MODE_PRIVATE)
    private val guardian=MemoryPressureGuardian(app)
    private val http=ResilientHttp(gate=guardian)
    private val compact=CompactDatasetSource(app,http,{ store().databaseName!! },{ settings })
    private fun seeker() = HuggingFaceDatasetSeeker(
        http,
        hub = (PluginRegistry.activeDataSourceEndpoint()?.trimEnd('/') ?: "https://huggingface.co/api/datasets").plus("/").toHttpUrl(),
        compact = compact::acquire
    )
    private val models=DualModelOrchestrator(app,ResearchGate { guardian.checkInference() })
    private val executor=Executors.newSingleThreadExecutor { task->Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);task.run() },"Girassol-Research") }
    private val generation=AtomicLong()
    private val reflectionGeneration=AtomicLong()
    private val reflectionLock=Any()
    private val reflectionPending=linkedMapOf<String,String>()
    private val reflectionCooldown=ConcurrentHashMap<String,Long>()
    private val reflectionExecutor=Executors.newSingleThreadExecutor { task->
        Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); task.run() },"Girassol-Inner-Speech")
    }
    @Volatile private var reflectionFuture: Future<*>?=null
    @Volatile private var future: Future<*>?=null
    private var cooldown=0L
    private var retryAt=0L
    private var settings=loadSettings()
    private var evidence: StudyEvidence?=null
    private var studying: KnowledgeCandidate?=null
    private val curriculum=EpistemicCurriculumEngine()
    private sealed interface SearchResult {
        data class Download(val batch: DatasetBatch): SearchResult
        data class Local(val matches: List<Pair<KnowledgeCandidate,Float>>): SearchResult
    }
    init { PluginRegistry.initialize(app); guardian.onRevoked { http.cancel();models.cancel();runCatching { NonRootSwapArena.cancelAll() };handler.post { cancel() } };configure(settings) }
    private fun loadSettings()=ResearchSettings(prefs.getBoolean("auto",false),prefs.getBoolean("wifi",true),prefs.getBoolean("review",true),prefs.getInt("quota",512).takeIf { it in listOf(256,512,1024,2048,4096) } ?: 512)
    fun configure(value: ResearchSettings) {
        require(value.valid());settings=value;guardian.wifiOnly=value.wifiOnly;guardian.enabled=value.automatic;guardian.resume()
        prefs.edit().putBoolean("auto",value.automatic).putBoolean("wifi",value.wifiOnly).putBoolean("review",value.quarantine).putInt("quota",value.quotaMb).apply()
        if(!value.automatic) cancel()
        ResearchHub.mutable.value=ResearchHub.mutable.value.copy(settings=settings)
    }
    fun control(id: String,pinned: Boolean,enabled: Boolean) { cancel();store().research().control(id,pinned,enabled);refresh() }
    fun refresh() { ResearchHub.mutable.value=ResearchHub.mutable.value.copy(settings=settings,entries=store().research().entries(),bytes=store().research().bytes()+compact.bytes()) }
    private fun message(value: String,busy: Boolean=false) { ResearchHub.mutable.value=ResearchHub.mutable.value.copy(message=value,busy=busy) }
    fun cancel() {
        generation.incrementAndGet()
        reflectionGeneration.incrementAndGet()
        http.cancel();models.cancel()
        runCatching { NonRootSwapArena.cancelAll() }
        future?.cancel(true);future=null
        reflectionFuture?.cancel(true);reflectionFuture=null
        synchronized(reflectionLock) { reflectionPending.clear() }
        evidence=null;studying=null
        message("Pesquisa suspensa; evidências recebidas preservadas.")
    }

    /**
     * The GNW threshold schedules a single low-priority GGUF reflection. Its result
     * returns as bounded canonical input on a later tick; no inference blocks the
     * world writer or mutates the state from the worker thread.
     */
    fun deliberate(world: WorldState,frame: CognitiveFrame): CognitiveFrame {
        val completed=synchronized(reflectionLock) {
            val ready=reflectionPending.toMap()
            ready
        }
        val candidate=frame.minds.entries
            .filter { (id,mind) -> mind.psyche.ignitionIndex>.65 && world.tick >= (reflectionCooldown[id] ?: 0L) }
            .sortedWith(compareByDescending<Map.Entry<String,MindState>> { it.value.psyche.ignitionIndex }.thenBy { it.key })
            .firstOrNull()
        if(candidate!=null && (reflectionFuture==null || reflectionFuture?.isDone==true)) {
            val token=reflectionGeneration.get()
            val agentId=candidate.key
            val mind=candidate.value
            reflectionCooldown[agentId]=world.tick+60
            reflectionFuture=reflectionExecutor.submit {
                try {
                    guardian.checkInference()
                    val thought=models.innerSpeech(world,agentId,mind)
                    synchronized(reflectionLock) {
                        if(token==reflectionGeneration.get()) reflectionPending[agentId]=thought
                    }
                } catch(_: CancellationException) {
                    // The next owner tick will decide whether to retry.
                } catch(_: Exception) {
                    reflectionCooldown[agentId]=world.tick+120
                }
            }
        }
        return if(completed.isEmpty()) frame else frame.copy(innerSpeech=completed)
    }
    private fun <T> launch(operation: ()->T,onComplete: ((Boolean)->Unit)?=null,accept: (T)->Unit) {
        val token=generation.get();message("Pesquisa em andamento",true)
        future=executor.submit {
            try {
                guardian.checkLocal();val result=operation();guardian.checkLocal()
                handler.post { if(generation.get()==token) {
                    future=null
                    try { accept(result);refresh();message("Biblioteca atualizada");onComplete?.invoke(true) }
                    catch(e: Exception) { message(e.message ?: "Aquisição não gravada");onComplete?.invoke(false) }
                } }
            } catch(e: LinkageError) {
                handler.post { if(generation.get()==token) { future=null;message("Backend nativo indisponível: ${e.message}");retryAt=android.os.SystemClock.elapsedRealtime()+60000;onComplete?.invoke(false) } }
            } catch(e: Exception) {
                handler.post { if(generation.get()==token) { future=null;message(e.message ?: "Pesquisa interrompida");retryAt=android.os.SystemClock.elapsedRealtime()+60000;onComplete?.invoke(false) } }
            }
        }
    }
    /** Must run before the candidate transition. All live network changes become journal input. */
    fun enrich(world: WorldState,frame: CognitiveFrame): CognitiveFrame {
        if(!settings.automatic) return frame
        val source=evidence
        if(source!=null) {
            val lesson=source.lesson(world)
            if(lesson!=null) {
                val mind=frame.minds.getValue(lesson.researcher)
                val updated=NativeMind.study(mind,source.candidate.row.text,lesson.confidence,world.tick)
                return frame.copy(minds=frame.minds+(lesson.researcher to updated),lesson=lesson)
            }
        }
        val researcher=studying?.intent?.discoverer ?: store().research().candidates(1).firstOrNull()?.intent?.discoverer
        return if(researcher!=null && world.agents.any { it.id==researcher && it.acceptsNeuralActivity() }) frame.copy(decisions=frame.decisions+(researcher to Activity.STUDY)) else frame
    }
    fun committed(transition: Transition) {
        transition.event.cognition?.innerSpeech?.keys?.let { committedIds ->
            synchronized(reflectionLock) { committedIds.forEach(reflectionPending::remove) }
        }
        if(transition.event.cognition?.lesson!=null) { evidence=null;studying=null;refresh() }
    }
    fun consider(point: Checkpoint,actions: List<ActionRecord>) {
        if(!settings.automatic || future!=null || android.os.SystemClock.elapsedRealtime()<retryAt) return
        if(!PluginRegistry.isEnabled("research.huggingface")) { message("Fonte Hugging Face desativada nos plugins"); return }
        try { guardian.checkLocal() } catch(e: ResearchPaused) { message(e.message ?: "Aguardando recursos");return }
        val world=point.state;val tables=store().research()
        if(evidence!=null) return
        val approved=world.agents.firstNotNullOfOrNull { tables.approvedFor(it.id,world.knowledge.map { lesson->lesson.id }.toSet()) }
        if(approved!=null) {
            studying=approved
            val citizen=world.agents.first { it.id==approved.intent.discoverer }
            if(!citizen.acting || citizen.activity!=Activity.STUDY) return
            launch({ KnowledgeExperiment.evaluate(point,actions,approved) { Thread.currentThread().isInterrupted || !guardian.green() } }) { result->
                if(result==null) { tables.rejectExperiment(approved.id,store().load()?.state?.tick ?: world.tick);message("Aprendizado recusado pelo replay sem regressão");cooldown=world.tick+240;studying=null }
                else { evidence=result;message("Experimento aprovado; aguardando estudo na biblioteca") }
            }
            return
        }
        val candidate=tables.candidates().firstOrNull { it.row.text.toByteArray().size<=2800 }
        if(candidate!=null) {
            if(!settings.quarantine) { message("Quarentena preservada; revisão cruzada automática desativada");return }
            studying=candidate
            val reviewer=world.agents.filter { it.id!=candidate.intent.discoverer }.maxByOrNull { world.minds[it.id]?.personality?.get(1) ?: 0.0 } ?: return
            val database=store().databaseName!!
            launch({
                check(models.available()) { "Quarentena preservada: importe um modelo GGUF para revisão cruzada" }
                val query=models.embed(candidate.intent.queryEn)
                val established=WorldStore(app,database).use { read->read.research().similar(query.first,query.second,guardian::checkLocal).take(3).map { it.first.row.text } }
                models.review(candidate,reviewer.id,established)
            }) { reviewed->
                val approvedReview=EpistemicQuarantine.approved(reviewed.row,checkNotNull(reviewed.researcher),checkNotNull(reviewed.verifier))
                tables.review(reviewed,(store().load()?.state?.tick ?: world.tick)+1,approvedReview,(settings.quotaMb*1048576L-compact.bytes()).coerceAtLeast(0))
                val entry=tables.entry(reviewed.intent.id)
                if(entry!=null) tables.save(entry.copy(phase=if(approvedReview) ResearchPhase.REVIEWED else ResearchPhase.REJECTED,message=if(approvedReview) "Duas avaliações locais favoráveis; aguardando replay." else "A revisão não aprovou o trecho."))
                studying=null
            }
            return
        }
        if(world.tick<cooldown) return
        // Real precondition failures, low novelty and unresolved mastery can initiate a curriculum.
        val failure=actions.firstOrNull { it.outcome in setOf("PRECONDITION_FAILED","RESERVED") }
        val citizen=world.agents.firstOrNull { it.id==failure?.agentId } ?: world.agents.minByOrNull { world.minds[it.id]?.novelty ?: 1.0 } ?: return
        if(failure==null && (world.minds[citizen.id]?.novelty ?: 1.0)>.08) return
        val target=when(failure?.activity ?: citizen.activity) {
            Activity.BUILD->Domain.ENGINEERING;Activity.EAT,Activity.WATER->Domain.AGRICULTURE
            Activity.TALK->Domain.GOVERNANCE;Activity.COFFEE,Activity.SLEEP->Domain.MEDICINE
            Activity.WRITE->Domain.LANGUAGE;Activity.STUDY->Domain.PHYSICS
        }
        val intent=curriculum.intent(world.seed,world.tick,citizen.id,target,if(failure!=null) "${failure.activity.label}: ${failure.outcome}" else "Pouca novidade nas experiências recentes",tables.mastery())
        tables.save(ResearchEntry(intent,ResearchPhase.SEARCHING,"Procurando dados textuais licenciados"));refresh();cooldown=world.tick+240
        try { guardian.check() } catch(e: ResearchPaused) { message(e.message ?: "Aguardando conexão");return }
        val installed=tables.installedKeys()
        val database=store().databaseName!!
        launch({
            val local=if(models.available()) {
                val query=models.embed(intent.queryEn)
                WorldStore(app,database).use { read->read.research().similar(query.first,query.second,guardian::checkLocal) }
            } else emptyList()
            if(local.firstOrNull()?.second?.let { it>=.62f }==true) return@launch SearchResult.Local(local)
            val found=seeker().discover(intent) { key->key in installed || installed.any { it.repo==key.repo && it.queryHash==key.queryHash && it.config.startsWith("file:") } } ?: throw java.io.IOException("Nenhum dataset público elegível encontrado")
            SearchResult.Download(seeker().acquire(found.first,found.second,intent))
        }) { result->
            when(result) {
                is SearchResult.Download->{ val current=store().load() ?: point;tables.acquire(intent,result.batch,current,(settings.quotaMb*1048576L-compact.bytes()).coerceAtLeast(0)) }
                is SearchResult.Local->tables.save(ResearchEntry(intent,ResearchPhase.VALIDATED,"Evidência já disponível na biblioteca; nenhuma aquisição necessária.",result.matches.first().first.repo))
            }
        }
    }
    private data class LocalDocument(val name: String,val bytes: ByteArray,val text: String)

    /**
     * Import a user-selected TXT/Markdown/CSV/JSON or EPUB through Android SAF.
     * The original bytes are bounded by the immutable acquisition journal's
     * current 2 MiB record limit; imported text enters the same quarantine and
     * hash-chain as remote HF acquisitions.
     */
    fun importDocument(uri: Uri,onComplete: (Boolean)->Unit) {
        val point=store().load() ?: throw IllegalStateException("A biosfera ainda não tem checkpoint")
        val world=point.state
        val discoverer=world.agents.firstOrNull()?.id ?: throw IllegalStateException("A biosfera não tem habitantes")
        launch(
            operation={
                val document=readLocalDocument(uri)
                val cleaned=document.text.replace("\u0000","").trim()
                require(cleaned.length>=16) { "O documento não contém texto suficiente para estudo" }
                val chunks=cleaned.chunked(30000).filter { it.length>=16 }
                require(chunks.size<=100) { "O documento excede o limite de 100 trechos do journal atual" }
                require(chunks.isNotEmpty()) { "Nenhum trecho utilizável foi extraído" }
                val rowElements=chunks.mapIndexed { index,chunk ->
                    JsonObject(mapOf(
                        "row_idx" to JsonPrimitive(index),
                        "row" to JsonObject(mapOf("text" to JsonPrimitive(chunk)))
                    ))
                }
                val raw=JsonObject(mapOf("rows" to JsonArray(rowElements))).toString().toByteArray(Charsets.UTF_8)
                require(raw.size<=2*1024*1024) { "O conteúdo textual excede a cota por aquisição do diário atual" }
                val sourceHash=sha256(document.bytes)
                val queryHash=sha256("${document.name.lowercase()}:$sourceHash".toByteArray())
                val intentId=sha256("local:${world.seed}:${world.tick}:$discoverer:$queryHash".toByteArray())
                val intent=DatasetResearchIntent(
                    id=intentId,
                    discoverer=discoverer,
                    tick=world.tick,
                    domain=Domain.LANGUAGE,
                    reason="Arquivo entregue pelo usuário via Storage Access Framework: ${document.name}",
                    queryPt="conteúdo e conhecimento do documento ${document.name}",
                    queryEn="document content and knowledge from ${document.name}",
                    queryHash=queryHash
                )
                val endpoint="https://local.girassol.invalid/import?offset=0"
                val repo=DatasetRepo(
                    id="local/${sourceHash.take(24)}",
                    revision=sourceHash,
                    downloads=0,
                    license="user-provided",
                    tasks=listOf("text"),
                    score=1.0
                )
                val rows=DatasetText.rows(raw,0)
                require(rows.isNotEmpty())
                val batch=DatasetBatch(
                    repo=repo,
                    subset=DatasetSplit("local","document"),
                    queryHash=queryHash,
                    endpoint=endpoint,
                    receivedAt=System.currentTimeMillis(),
                    payloadHash=sha256(raw),
                    raw=raw,
                    rows=rows
                )
                intent to batch
            },
            accept={ (intent,batch)->
                val current=store().load() ?: point
                require(current.state.agents.any { it.id==intent.discoverer }) { "O cidadão solicitante não existe mais nesta linha temporal" }
                store().research().acquire(intent,batch,current,settings.quotaMb*1048576L)
                message("Documento recebido e colocado em quarentena: ${batch.rows.size} trechos")
            },
            onComplete=onComplete
        )
    }

    private fun readLocalDocument(uri: Uri): LocalDocument {
        val name=app.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { cursor ->
            if(cursor.moveToFirst()) cursor.getString(0) else null
        } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "documento"
        val input=app.contentResolver.openInputStream(uri) ?: throw java.io.IOException("Não foi possível abrir o arquivo")
        val bytes=ByteArrayOutputStream()
        input.use { stream ->
            val buffer=ByteArray(8192)
            while(true) {
                val read=stream.read(buffer)
                if(read<0) break
                require(bytes.size().toLong()+read<=2L*1024*1024) { "Arquivo maior que o limite atual do diário imutável (2 MiB)" }
                bytes.write(buffer,0,read)
            }
        }
        val data=bytes.toByteArray()
        val format = OfflineDocumentFormat.fromName(name)
        val text = when(format) {
            OfflineDocumentFormat.TXT -> data.toString(Charsets.UTF_8)
            OfflineDocumentFormat.EPUB -> extractEpub(data)
            OfflineDocumentFormat.PDF -> extractPdf(data)
            OfflineDocumentFormat.PARQUET -> extractParquet(data)
        }
        return LocalDocument(name,data,text)
    }

    private fun extractPdf(bytes: ByteArray): String {
        PDFBoxResourceLoader.init(app)
        PDDocument.load(bytes).use { document ->
            require(!document.isEncrypted) { "PDF protegido por senha não pode ser importado" }
            return PDFTextStripper().getText(document).take(2 * 1024 * 1024)
        }
    }

    private fun extractParquet(bytes: ByteArray): String {
        val temporary = File.createTempFile("girassol-parquet-", ".parquet", app.cacheDir)
        try {
            temporary.writeBytes(bytes)
            val configuration = Configuration(false).apply {
                set("fs.defaultFS", "file:///")
                set("fs.file.impl", "org.apache.hadoop.fs.LocalFileSystem")
            }
            val output = StringBuilder()
            ParquetReader.builder<Group>(GroupReadSupport(), Path(temporary.absolutePath))
                .withConf(configuration).build().use { reader ->
                    var rows = 0
                    while (true) {
                        val record = reader.read() ?: break
                        output.append(record.toString()).append('\\n')
                        rows++
                        require(rows <= 100_000) { "Parquet excede 100 mil linhas" }
                        require(output.length <= 2 * 1024 * 1024) { "Texto Parquet excede 2 MiB" }
                    }
                }
            return output.toString()
        } finally { temporary.delete() }
    }

    private fun extractEpub(bytes: ByteArray): String {
        val output=StringBuilder()
        var expanded=0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while(true) {
                val entry=zip.nextEntry ?: break
                val name=entry.name.lowercase()
                if(!entry.isDirectory && (name.endsWith(".xhtml") || name.endsWith(".html") || name.endsWith(".htm"))) {
                    val buffer=ByteArrayOutputStream()
                    val chunk=ByteArray(8192)
                    while(true) {
                        val read=zip.read(chunk)
                        if(read<0) break
                        expanded+=read
                        require(expanded<=2L*1024*1024) { "EPUB descompactado excede a cota por aquisição" }
                        buffer.write(chunk,0,read)
                    }
                    val markup=buffer.toString(Charsets.UTF_8.name())
                    output.append(Html.fromHtml(markup,Html.FROM_HTML_MODE_COMPACT)).append('\n')
                }
                zip.closeEntry()
            }
        }
        return output.toString()
    }

    override fun close() { cancel();executor.shutdownNow();reflectionExecutor.shutdownNow();guardian.close() }
}
