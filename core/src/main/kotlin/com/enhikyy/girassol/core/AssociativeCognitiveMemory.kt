package com.enhikyy.girassol.core

import kotlinx.serialization.Serializable
import kotlin.math.abs

@Serializable data class AssociativeLink(val sourceEvent: Long,val targetEvent: Long,val similarity: Double)
@Serializable data class SemanticSchema(val action: Activity,val observations: Int,val expectedReward: Double,
    val lastSource: Long,val sources: List<Long>)
@Serializable data class RippleTrace(val tick: Long,val sources: List<Long>,val before: String,val after: String,val pulses: Int)

/** Directed bounded A-MEM neighborhoods over the actual structured episodic observations. */
object AssociativeCognitiveMemory {
    const val NEIGHBORS=4
    fun connect(bank: MemoryBank): MemoryBank {
        val episodes=bank.episodes
        if(episodes.isEmpty()) return bank.copy(associations=emptyList())
        val ids=episodes.map { it.eventId }.toSet()
        val known=bank.associations.filter { it.sourceEvent in ids && it.targetEvent in ids }
        val newest=episodes.last()
        if(known.any { it.sourceEvent==newest.eventId }) return bank.copy(associations=known)
        fun similarity(first: Episode,second: Episode): Double = first.embedding.zip(second.embedding).sumOf { (a,b) -> a.toDouble()*b }.coerceIn(-1.0,1.0)
        val outgoing=known.groupBy { it.sourceEvent }.mapValues { it.value.toMutableList() }.toMutableMap()
        for(other in episodes) if(other.eventId!=newest.eventId) {
            val score=similarity(newest,other)
            if(score>=.65) {
                outgoing.getOrPut(newest.eventId) { mutableListOf() }+=AssociativeLink(newest.eventId,other.eventId,score)
                outgoing.getOrPut(other.eventId) { mutableListOf() }+=AssociativeLink(other.eventId,newest.eventId,score)
            }
        }
        val links=outgoing.toSortedMap().flatMap { (_,edges) -> edges.distinctBy { it.targetEvent }
            .sortedWith(compareByDescending<AssociativeLink> { it.similarity }.thenBy { it.targetEvent }).take(NEIGHBORS) }
        check(links.size<=HippocampalSleepConsolidator.MAX_NODES_PER_AGENT*NEIGHBORS)
        return bank.copy(associations=links)
    }
    fun spreadingActivation(bank: MemoryBank,eventId: Long,tick: Long): Double {
        val ids=bank.episodes.map { it.eventId }.toSet()
        val active=bank.workingMemory.decay(tick,ids).slots.map { it.eventId }.toSet()
        return bank.associations.filter { it.sourceEvent==eventId && it.targetEvent in active }.maxOfOrNull { it.similarity } ?: 0.0
    }
    /** Semantics are learned only from replayed, committed evidence, once per event. */
    fun consolidate(bank: MemoryBank,selected: List<Episode>,tick: Long,before: String,after: String): MemoryBank {
        require(selected.size in 1..12 && selected.map { it.eventId }.distinct().size==selected.size)
        val schemas=bank.semantic.associateBy { it.action }.toMutableMap()
        for((action,events) in selected.groupBy { it.action }) {
            val old=schemas[action]
            val fresh=events.filter { it.eventId>(old?.lastSource ?: 0) }.sortedBy { it.eventId }
            if(fresh.isEmpty()) continue
            val n=(old?.observations ?: 0).toLong()+fresh.size
            val reward=((old?.expectedReward ?: 0.0)*(old?.observations ?: 0)+fresh.sumOf { it.reward })/n
            schemas[action]=SemanticSchema(action,n.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),reward,fresh.last().eventId,
                ((old?.sources ?: emptyList())+fresh.map { it.eventId }).distinct().takeLast(32))
        }
        return bank.copy(semantic=schemas.values.sortedBy { it.action.ordinal },ripple=RippleTrace(tick,selected.map { it.eventId },before,after,selected.size))
    }
    fun valid(bank: MemoryBank,tick: Long,ids: Set<Long>): Boolean =
        bank.associations.size<=2048 && bank.associations.map { it.sourceEvent to it.targetEvent }.distinct().size==bank.associations.size &&
            bank.associations.groupingBy { it.sourceEvent }.eachCount().values.all { it<=NEIGHBORS } && bank.associations.all {
                it.sourceEvent in ids && it.targetEvent in ids && it.sourceEvent!=it.targetEvent && it.similarity.isFinite() && it.similarity in .65..1.0 } &&
            bank.semantic.size<=8 && bank.semantic.map { it.action }.distinct().size==bank.semantic.size && bank.semantic.all {
                it.observations>0 && it.expectedReward.isFinite() && abs(it.expectedReward)<=5 && it.lastSource in 1..tick &&
                    it.sources.size in 1..32 && it.sources.distinct().size==it.sources.size && it.sources.all { source -> source in 1..it.lastSource } && it.sources.max()==it.lastSource } &&
            (bank.ripple?.let { it.tick in 0..tick && it.sources.size in 1..12 && it.pulses==it.sources.size && it.sources.distinct().size==it.sources.size &&
                it.sources.all { source -> source in 1..it.tick } && listOf(it.before,it.after).all { hash -> hash.matches(Regex("[a-f0-9]{64}")) } } ?: true)
}
