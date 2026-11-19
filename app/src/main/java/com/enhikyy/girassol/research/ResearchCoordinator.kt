package com.enhikyy.girassol.research

import android.content.Context
import android.os.Handler
import android.os.Process
import com.enhikyy.girassol.core.*
import com.enhikyy.girassol.data.WorldStore
import com.enhikyy.girassol.cognition.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicLong

internal data class ResearchStatus(val settings: ResearchSettings=ResearchSettings(),val entries: List<ResearchEntry> = emptyList(),
    val message: String="Ative a pesquisa para os habitantes consultarem o Hugging Face.",val bytes: Long=0,val busy: Boolean=false)
internal object ResearchHub { val mutable=MutableStateFlow(ResearchStatus());val state=mutable.asStateFlow() }

/** Async effects return to the sole world writer. A generation token prevents cross-timeline writes. */
internal class ResearchCoordinator(context: Context,private val handler: Handler,private val store: ()->WorldStore) : AutoCloseable {
    private val app=context.applicationContext
    private val prefs=app.getSharedPreferences("research",Context.MODE_PRIVATE)
    private val guardian=MemoryPressureGuardian(app)
    private val http=ResilientHttp(gate=guardian)
    private val seeker=HuggingFaceDatasetSeeker(http)
    private val models=DualModelOrchestrator(app,ResearchGate { guardian.checkLocal() })
    private val executor=Executors.newSingleThreadExecutor { task->Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);task.run() },"Girassol-Research") }
    private val generation=AtomicLong()
    @Volatile private var future: Future<*>?=null
    private var cooldown=0L
    private var retryAt=0L
    private var settings=loadSettings()
    private var evidence: StudyEvidence?=null
    private var studying: KnowledgeCandidate?=null
    private val curriculum=EpistemicCurriculumEngine()
    init { guardian.onRevoked { http.cancel();models.cancel();handler.post { cancel() } };configure(settings) }
    private fun loadSettings()=ResearchSettings(prefs.getBoolean("auto",false),prefs.getBoolean("wifi",true),prefs.getBoolean("review",true),prefs.getInt("quota",512).takeIf { it in listOf(256,512,1024,2048,4096) } ?: 512)
    fun configure(value: ResearchSettings) {
        require(value.valid());settings=value;guardian.wifiOnly=value.wifiOnly;guardian.enabled=value.automatic;guardian.resume()
        prefs.edit().putBoolean("auto",value.automatic).putBoolean("wifi",value.wifiOnly).putBoolean("review",value.quarantine).putInt("quota",value.quotaMb).apply()
        if(!value.automatic) cancel()
        ResearchHub.mutable.value=ResearchHub.mutable.value.copy(settings=settings)
    }
    fun refresh() { ResearchHub.mutable.value=ResearchHub.mutable.value.copy(settings=settings,entries=store().research().entries(),bytes=store().research().bytes()) }
    private fun message(value: String,busy: Boolean=false) { ResearchHub.mutable.value=ResearchHub.mutable.value.copy(message=value,busy=busy) }
    fun cancel() { generation.incrementAndGet();http.cancel();models.cancel();future?.cancel(true);future=null;evidence=null;studying=null;message("Pesquisa suspensa; evidências recebidas preservadas.") }
    private fun <T> launch(operation: ()->T,accept: (T)->Unit) {
        val token=generation.get();message("Pesquisa em andamento",true)
        future=executor.submit {
            try {
                guardian.checkLocal();val result=operation();guardian.checkLocal()
                handler.post { if(generation.get()==token) { future=null;try { accept(result);refresh();message("Biblioteca atualizada") } catch(e: Exception) { message(e.message ?: "Aquisição não gravada") } } }
            } catch(e: LinkageError) {
                handler.post { if(generation.get()==token) { future=null;message("Backend nativo indisponível: ${e.message}");retryAt=android.os.SystemClock.elapsedRealtime()+60000 } }
            } catch(e: Exception) {
                handler.post { if(generation.get()==token) { future=null;message(e.message ?: "Pesquisa interrompida");retryAt=android.os.SystemClock.elapsedRealtime()+60000 } }
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
    fun committed(transition: Transition) { if(transition.event.cognition?.lesson!=null) { evidence=null;studying=null;refresh() } }
    fun consider(point: Checkpoint,actions: List<ActionRecord>) {
        if(!settings.automatic || future!=null || android.os.SystemClock.elapsedRealtime()<retryAt) return
        try { guardian.checkLocal() } catch(e: ResearchPaused) { message(e.message ?: "Aguardando recursos");return }
        val world=point.state;val tables=store().research()
        if(evidence!=null) return
        val approved=world.agents.firstNotNullOfOrNull { tables.approvedFor(it.id,world.knowledge.map { lesson->lesson.id }.toSet()) }
        if(approved!=null) {
            studying=approved
            val citizen=world.agents.first { it.id==approved.intent.discoverer }
            if(!citizen.acting || citizen.activity!=Activity.STUDY) return
            launch({ KnowledgeExperiment.evaluate(point,actions,approved) { Thread.currentThread().isInterrupted || !guardian.green() } }) { result->
                if(result==null) { message("Aprendizado recusado pelo replay sem regressão");cooldown=world.tick+240;studying=null }
                else { evidence=result;message("Experimento aprovado; aguardando estudo na biblioteca") }
            }
            return
        }
        val candidate=tables.candidates().firstOrNull { it.row.text.toByteArray().size<=2800 }
        if(candidate!=null) {
            if(!settings.quarantine) { message("Quarentena preservada; revisão cruzada automática desativada");return }
            studying=candidate
            val reviewer=world.agents.filter { it.id!=candidate.intent.discoverer }.maxByOrNull { world.minds[it.id]?.personality?.get(1) ?: 0.0 } ?: return
            val established=world.knowledge.takeLast(3).mapNotNull { k->tables.snippets(candidate.intent.id).firstOrNull { it.first.id==k.id }?.first?.row?.text }
            launch({
                check(models.available()) { "Quarentena preservada: importe um modelo GGUF para revisão cruzada" }
                models.review(candidate,reviewer.id,established)
            }) { reviewed->
                val approvedReview=EpistemicQuarantine.approved(reviewed.row,checkNotNull(reviewed.researcher),checkNotNull(reviewed.verifier))
                tables.review(reviewed,store().load()?.state?.tick ?: world.tick,approvedReview)
                val entry=tables.entry(reviewed.intent.id)
                if(entry!=null) tables.save(entry.copy(phase=if(approvedReview) ResearchPhase.VALIDATED else ResearchPhase.REJECTED,message=if(approvedReview) "Duas avaliações locais favoráveis; aguardando replay." else "A revisão não aprovou o trecho."))
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
        launch({
            val found=seeker.discover(intent) { key->key in installed } ?: throw java.io.IOException("Nenhum dataset público elegível encontrado")
            seeker.acquire(found.first,found.second,intent)
        }) { batch->
            val current=store().load() ?: point
            tables.acquire(intent,batch,current,settings.quotaMb*1048576L)
        }
    }
    override fun close() { cancel();executor.shutdownNow();guardian.close() }
}
