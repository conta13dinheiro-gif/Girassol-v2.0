package com.enhikyy.girassol.research

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

@Serializable data class AcquisitionEnvelope(val tick: Long,val eventHash: String,val repo: String,val revision: String,
    val config: String,val split: String,val queryHash: String,val endpoint: String,val license: String,val bytes: Int,
    val payloadHash: String,val previousHash: String)
data class SealedAcquisition(val envelope: AcquisitionEnvelope,val compressed: ByteArray,val hash: String)
object KnowledgeJournal {
    private val json=Json { encodeDefaults=true }
    fun envelopeBytes(envelope: AcquisitionEnvelope) = json.encodeToString(envelope).toByteArray()
    fun seal(batch: DatasetBatch,tick: Long,eventHash: String,previousHash: String): SealedAcquisition {
        require(tick>=0 && eventHash.matches(Regex("[0-9a-f]{64}")) && previousHash.matches(Regex("[0-9a-f]{64}")))
        require(batch.raw.size<=2*1024*1024 && sha256(batch.raw)==batch.payloadHash)
        val e=AcquisitionEnvelope(tick,eventHash,batch.repo.id,batch.repo.revision,batch.subset.config,batch.subset.split,
            batch.queryHash,batch.endpoint,batch.repo.license,batch.raw.size,batch.payloadHash,previousHash)
        val output=ByteArrayOutputStream(); DeflaterOutputStream(output).use { it.write(batch.raw) }
        return SealedAcquisition(e,output.toByteArray(),sha256(envelopeBytes(e)))
    }
    fun restore(sealed: SealedAcquisition): ByteArray {
        val e=sealed.envelope
        require(e.bytes in 0..2*1024*1024 && sha256(envelopeBytes(e))==sealed.hash) { "Aquisição adulterada" }
        val output=ByteArrayOutputStream(e.bytes)
        InflaterInputStream(sealed.compressed.inputStream()).use { stream->
            val buffer=ByteArray(8192)
            while(true) { val read=stream.read(buffer);if(read<0) break;require(output.size().toLong()+read<=e.bytes);output.write(buffer,0,read) }
        }
        return output.toByteArray().also { require(it.size==e.bytes && sha256(it)==e.payloadHash) { "Lote comprimido danificado" } }
    }
}
data class CacheEntry(val id: String,val bytes: Long,val accessed: Long,val pinned: Boolean=false)
object QuotaLru {
    /** Immutable journal bytes count toward the quota and can never be selected for eviction. */
    fun victims(entries: List<CacheEntry>,immutableBytes: Long,incoming: Long,quota: Long): List<String> {
        require(immutableBytes>=0 && incoming>=0 && quota>=0 && entries.all { it.bytes>=0 })
        var used=Math.addExact(immutableBytes,incoming)
        entries.forEach { used=Math.addExact(used,it.bytes) }
        val victims=mutableListOf<String>()
        for(entry in entries.filter { !it.pinned }.sortedWith(compareBy<CacheEntry> { it.accessed }.thenBy { it.id })) {
            if(used<=quota) break
            used-=entry.bytes; victims+=entry.id
        }
        if(used>quota) throw ResearchPaused("A cota preserva dados fixados e o diário; libere espaço ou aumente a cota.")
        return victims
    }
}
@Serializable enum class Verdict { APPROVE, REJECT, UNCERTAIN }
@Serializable data class KnowledgeReview(val agent: String,val modelHash: String,val rowHash: String,val verdict: Verdict,val confidence: Int)
object EpistemicQuarantine {
    fun approved(row: DatasetRow,researcher: KnowledgeReview,reviewer: KnowledgeReview): Boolean =
        sha256(row.text.toByteArray())==row.digest && researcher.agent.isNotBlank() && reviewer.agent.isNotBlank() &&
        researcher.agent!=reviewer.agent && listOf(researcher,reviewer).all {
            it.rowHash==row.digest && it.modelHash.matches(Regex("[0-9a-f]{64}")) && it.verdict==Verdict.APPROVE && it.confidence in 90..100
        }
}
