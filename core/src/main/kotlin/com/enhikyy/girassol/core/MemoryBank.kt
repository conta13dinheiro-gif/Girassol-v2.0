package com.enhikyy.girassol.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlin.math.*

/** Structured observations, not language-model embeddings. All changes are recorded in frame v4. */
@Serializable data class Episode(val tick: Long,val eventId: Long,val action: Activity,val reward: Double,
    val x: Double,val z: Double,val emotion: Double,val importance: Double,val embedding: List<Float>)
@Serializable data class Reflection(val action: Activity,val observations: Int,val expectedReward: Double,val lastTick: Long,val sourceEvent: Long)
@Serializable data class Procedure(val action: Activity,val successes: Int,val expectedReward: Double,val lastTick: Long,
    val sourceEvent: Long,val teacher: String? = null)

/** Value estimate for one recalled episode; updated only after a later, committed outcome. */
@Serializable data class MemoryUtility(
    val eventId: Long,
    val qValue: Double = 0.0,
    val retrievals: Int = 0,
    val lastRetrievedTick: Long = -1,
    val lastFeedbackTick: Long = -1
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable data class MemoryBank(
    val episodes: List<Episode> = emptyList(),
    val reflections: List<Reflection> = emptyList(),
    val procedures: List<Procedure> = emptyList(),
    val reflectedTick: Long = -1,
    // Omit empty defaults so legacy canonical event hashes remain byte-for-byte stable.
    @EncodeDefault(EncodeDefault.Mode.NEVER) val utilities: List<MemoryUtility> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val pendingRecallEventIds: List<Long> = emptyList()
) {
    fun valid(tick: Long): Boolean = reflectedTick in -1..tick && episodes.all { e->
        e.tick in 0..tick && e.eventId in 1..tick && e.reward.isFinite() && e.reward in -5.0..5.0 && e.emotion.isFinite() && e.emotion in -1.0..1.0 &&
        e.x.isFinite() && e.z.isFinite() && e.importance.isFinite() && e.importance in 0.0..1.0 && e.embedding.size==16 &&
        e.embedding.all { it.isFinite() } && abs(e.embedding.sumOf { it.toDouble()*it }-1)<.002
    } && reflections.map { it.action }.distinct().size==reflections.size && reflections.all {
        it.observations>0 && it.expectedReward.isFinite() && it.expectedReward in -5.0..5.0 && it.lastTick in 0..tick && it.sourceEvent in 1..tick
    } && procedures.map { it.action }.distinct().size==procedures.size && procedures.all {
        it.successes>=3 && it.expectedReward.isFinite() && it.expectedReward in -5.0..5.0 && it.lastTick in 0..tick && it.sourceEvent in 1..tick
    } && utilities.map { it.eventId }.distinct().size==utilities.size && utilities.size<=HippocampalSleepConsolidator.MAX_NODES_PER_AGENT &&
        utilities.all { utility ->
            utility.eventId in episodes.map { it.eventId }.toSet() && utility.qValue.isFinite() && utility.qValue in -5.0..5.0 &&
                utility.retrievals>0 && utility.lastRetrievedTick in 0..tick && utility.lastFeedbackTick in -1..tick
        } && pendingRecallEventIds.distinct().size==pendingRecallEventIds.size && pendingRecallEventIds.size<=MAX_PENDING_RECALLS &&
        pendingRecallEventIds.all { id -> utilities.any { it.eventId==id } }

    fun snapshot() = copy(
        episodes=episodes.map { it.copy(embedding=it.embedding.toList()) },
        reflections=reflections.toList(),
        procedures=procedures.toList(),
        utilities=utilities.toList(),
        pendingRecallEventIds=pendingRecallEventIds.toList()
    )

    /**
     * Phase B of value-aware episodic recall. Similarity first narrows candidates;
     * the learned utility then re-ranks them. Unknown memories start at neutral Q=0.
     */
    fun utilityAdjustedScore(eventId: Long, similarity: Double, utilityWeight: Double = DEFAULT_UTILITY_WEIGHT): Double {
        require(similarity.isFinite() && similarity in -1.0..1.0)
        require(utilityWeight.isFinite() && utilityWeight in 0.0..1.0)
        val learned = utilities.firstOrNull { it.eventId==eventId }?.qValue ?: 0.0
        return (1.0-utilityWeight)*similarity + utilityWeight*(learned/5.0).coerceIn(-1.0,1.0)
    }

    /** Record the selected memories so the next committed outcome can assign credit. */
    fun recordRecall(eventIds: List<Long>, tick: Long): MemoryBank {
        require(tick>=0)
        val known = eventIds.distinct().filter { id -> episodes.any { it.eventId==id } }.take(MAX_PENDING_RECALLS)
        if (known.isEmpty()) return this
        val byId = utilities.associateBy { it.eventId }.toMutableMap()
        known.forEach { id ->
            val old = byId[id] ?: MemoryUtility(id)
            byId[id] = old.copy(retrievals=(old.retrievals+1).coerceAtMost(Int.MAX_VALUE),lastRetrievedTick=tick)
        }
        val retainedUtilities = byId.values
            .sortedWith(compareByDescending<MemoryUtility> { it.lastRetrievedTick }.thenBy { it.eventId })
            .take(HippocampalSleepConsolidator.MAX_NODES_PER_AGENT)
            .sortedBy { it.eventId }
        val retainedIds = retainedUtilities.map { it.eventId }.toSet()
        val pending = (pendingRecallEventIds + known).distinct().filter { it in retainedIds }.takeLast(MAX_PENDING_RECALLS)
        return copy(utilities=retainedUtilities,pendingRecallEventIds=pending)
    }

    fun observe(world: WorldState,citizen: Citizen,record: ActionRecord?): MemoryBank {
        val last=citizen.memories.lastOrNull()?.takeIf { it.tick==world.tick }
        val failed=record?.outcome=="PRECONDITION_FAILED" || record?.outcome=="RESERVED"
        var next=this
        if((last!=null || failed) && episodes.none { it.tick==world.tick }) {
            val reward=last?.reward ?: if(record?.outcome=="PRECONDITION_FAILED") -.15 else -.03
            val importance=(.15+abs(reward)*1.8+if(failed) .2 else 0.0).coerceIn(0.0,1.0)
            val episode=Episode(world.tick,last?.eventId ?: world.tick,citizen.activity,reward,citizen.x,citizen.z,
                (reward*2).coerceIn(-1.0,1.0),importance,embedding(citizen,citizen.activity,reward))
            next=copy(episodes=episodes+episode)
            // Delayed reward updates only memories actually selected before this outcome.
            if (pendingRecallEventIds.isNotEmpty()) {
                val byId=next.utilities.associateBy { it.eventId }.toMutableMap()
                pendingRecallEventIds.forEach { id ->
                    val old=byId[id] ?: return@forEach
                    val alpha=MEMRL_ALPHA
                    byId[id]=old.copy(qValue=(old.qValue+alpha*(reward-old.qValue)).coerceIn(-5.0,5.0),lastFeedbackTick=world.tick)
                }
                next=next.copy(
                    utilities=byId.values.sortedBy { it.eventId },
                    pendingRecallEventIds=emptyList()
                )
            }
            if(reward>0) {
                val previous=procedures.firstOrNull { it.action==episode.action }
                // Count actual validated successful experiences, never a claimed invention.
                val successes=citizen.memories.count { it.action==episode.action && it.reward>0 }
                if(previous!=null || successes>=3) {
                    val count=(previous?.successes ?: (successes-1)).coerceAtMost(Int.MAX_VALUE-1)+1
                    val average=previous?.let { it.expectedReward+(reward-it.expectedReward)/count } ?: reward
                    next=next.copy(procedures=next.procedures.filter { it.action!=episode.action }+Procedure(episode.action,count,average,world.tick,episode.eventId))
                }
            }
        }
        val sleeping=citizen.acting && citizen.activity==Activity.SLEEP
        val due=world.tick-next.reflectedTick>=1440 || (sleeping && next.episodes.any { it.tick>next.reflectedTick })
        if(due) {
            val fresh=next.episodes.filter { it.tick>next.reflectedTick }
            val lessons=next.reflections.associateBy { it.action }.toMutableMap()
            fresh.groupBy { it.action }.forEach { (action,events)->
                val old=lessons[action]; val n=(old?.observations ?: 0).toLong()+events.size
                val value=((old?.expectedReward ?: 0.0)*(old?.observations ?: 0)+events.sumOf { it.reward })/n
                lessons[action]=Reflection(action,n.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),value,world.tick,events.last().eventId)
            }
            // Forget only experiences already distilled; preserve consequential negative outcomes longer.
            next=next.copy(reflections=lessons.values.sortedBy { it.action.ordinal },reflectedTick=world.tick,
                episodes=next.episodes.filter { it.importance*exp(-(world.tick-it.tick)/2880.0)>=.04 })
        }
        if (due || next.episodes.size > HippocampalSleepConsolidator.MAX_NODES_PER_AGENT) {
            next = HippocampalSleepConsolidator.consolidate(next, world.tick).bank
        }
        return next
    }

    fun learnFrom(teacherId: String,teacher: MemoryBank,tick: Long): MemoryBank {
        val learned=procedures.associateBy { it.action }.toMutableMap()
        teacher.procedures.filter { p-> p.successes>=3 && p.expectedReward>0 && (learned[p.action]?.successes ?: 0)<p.successes }.forEach { p->
            learned[p.action]=p.copy(teacher=teacherId,lastTick=tick)
        }
        return copy(procedures=learned.values.sortedBy { it.action.ordinal })
    }

    companion object {
        const val MAX_PENDING_RECALLS = 8
        const val MEMRL_ALPHA = 0.2
        const val DEFAULT_UTILITY_WEIGHT = 0.25

        fun embedding(citizen: Citizen,activity: Activity = citizen.activity,reward: Double=0.0): List<Float> {
            val n=citizen.needs
            val raw=List(8) { if(it==activity.ordinal) 1.0 else 0.0 }+listOf(
                (citizen.x/100).coerceIn(-1.0,1.0),(citizen.z/100).coerceIn(-1.0,1.0),
                n.energy,n.hydration,n.glucose,n.social,n.curiosity,reward.coerceIn(-1.0,1.0))
            val norm=sqrt(raw.sumOf { it*it }); return raw.map { (it/norm).toFloat() }
        }
    }
}
