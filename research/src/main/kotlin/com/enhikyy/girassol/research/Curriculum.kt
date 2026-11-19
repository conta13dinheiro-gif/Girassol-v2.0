package com.enhikyy.girassol.research

import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.util.Locale

fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
@Serializable enum class Domain(val title: String,val portuguese: String,val english: String) {
    LOGIC("Lógica","lógica raciocínio dedutivo","logic deductive reasoning"),
    LANGUAGE("Linguagem","linguagem compreensão de texto","language reading comprehension"),
    ECOLOGY("Ecologia","ecologia conservação de recursos","ecology resource conservation"),
    PHYSICS("Física","física energia materiais","physics energy materials"),
    AGRICULTURE("Agricultura","agricultura solo cultivo","agriculture soil crops"),
    ENGINEERING("Engenharia modular","engenharia construção modular","engineering modular construction"),
    ETHICS("Ética","ética cooperação responsabilidade","ethics cooperation responsibility"),
    MUSIC("Música","música harmonia acústica","music harmony acoustics"),
    MEDICINE("Medicina","medicina saúde fisiologia","medicine health physiology"),
    GOVERNANCE("Governança","governança instituições cooperação","governance institutions cooperation");
    val prerequisites: List<Domain> get() = when(this) {
        PHYSICS->listOf(LOGIC); AGRICULTURE->listOf(ECOLOGY)
        ENGINEERING->listOf(PHYSICS,LOGIC); ETHICS->listOf(LANGUAGE)
        MUSIC->listOf(PHYSICS,LANGUAGE); MEDICINE->listOf(ECOLOGY,LOGIC)
        GOVERNANCE->listOf(ETHICS,LOGIC); else->emptyList()
    }
}
@Serializable data class DatasetResearchIntent(val id: String,val discoverer: String,val tick: Long,val domain: Domain,
    val reason: String,val queryPt: String,val queryEn: String,val queryHash: String)
class EpistemicCurriculumEngine {
    fun blocker(target: Domain,mastery: Map<Domain,Double>): Domain {
        require(mastery.values.all { it.isFinite() && it in 0.0..1.0 })
        val missing=target.prerequisites.filter { (mastery[it] ?: 0.0)<.6 }.minByOrNull { mastery[it] ?: 0.0 }
        return missing?.let { blocker(it,mastery) } ?: target
    }
    fun intent(seed: Long,tick: Long,agent: String,target: Domain,reason: String,mastery: Map<Domain,Double>): DatasetResearchIntent {
        require(tick>=0 && agent.isNotBlank() && reason.isNotBlank())
        val node=blocker(target,mastery)
        val queryHash=sha256((node.portuguese.lowercase(Locale.ROOT)+"\n"+node.english.lowercase(Locale.ROOT)).toByteArray())
        val id=sha256("$seed:$tick:$agent:${node.name}:$queryHash".toByteArray())
        return DatasetResearchIntent(id,agent,tick,node,reason,node.portuguese,node.english,queryHash)
    }
}
@Serializable data class DatasetRepo(val id: String,val revision: String,val downloads: Long,val license: String,
    val tasks: List<String>,val score: Double)
@Serializable data class DatasetSplit(val config: String,val split: String)
@Serializable data class DatasetRow(val index: Long,val text: String,val digest: String)
@Serializable data class DatasetBatch(val repo: DatasetRepo,val subset: DatasetSplit,val queryHash: String,
    val endpoint: String,val receivedAt: Long,val payloadHash: String,val raw: ByteArray,val rows: List<DatasetRow>)
@Serializable data class DatasetKey(val repo: String,val config: String,val split: String,val queryHash: String)
