package com.enhikyy.girassol.research

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

@Serializable data class ResearchSettings(val automatic: Boolean=false,val wifiOnly: Boolean=true,
    val quarantine: Boolean=true,val quotaMb: Int=512) {
    fun valid() = quotaMb in listOf(256,512,1024,2048,4096)
}
@Serializable enum class ResearchPhase(val title: String) {
    SEARCHING("Buscando"), STREAMING("Recebendo trechos"), QUARANTINED("Em quarentena"),
    REVIEWING("Validação cruzada"), REVIEWED("Revisões concluídas"), VALIDATED("Validado e indexado"), LEARNED("Pesos atualizados"),
    PAUSED("Suspenso"), REJECTED("Recusado"), FAILED("Não concluído")
}
@Serializable data class ResearchEntry(val intent: DatasetResearchIntent,val phase: ResearchPhase,val message: String,
    val repo: String?=null,val acquired: Int=0,val learned: Int=0,val pinned: Boolean=false)
@Serializable data class KnowledgeCandidate(val id: String,val acquisitionHash: String,val intent: DatasetResearchIntent,
    val repo: String,val license: String,val row: DatasetRow,val researcher: KnowledgeReview?=null,val verifier: KnowledgeReview?=null,val embedding: List<Float> = emptyList(),val embeddingModel: String="")
object ResearchCodec {
    val json=Json { encodeDefaults=true; ignoreUnknownKeys=true }
    inline fun <reified T> encode(value: T): String = json.encodeToString(value)
    inline fun <reified T> decode(value: String): T = json.decodeFromString(value)
}
object MemoryPressurePolicy {
    const val GREEN_BYTES=1100L*1024*1024
    fun green(available: Long,lowMemory: Boolean,thermal: Int) = available>GREEN_BYTES && !lowMemory && thermal<2
}
