package com.enhikyy.girassol.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlin.math.*

/** Serialized learned state, distinct from inherited loci. Native output is journal input. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable data class MindState(val parameters: List<Double>,val hidden: List<Double>,
    val personality: List<Double>,val exposure: List<Int> = List(8) { 0 },
    val learnedTick: Long = -1,val novelty: Double = 0.0,val behaviors: List<List<Double>> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val memory: MemoryBank = MemoryBank()) {
    fun valid(tick: Long): Boolean = parameters.size==248 && parameters.all { it.isFinite() && abs(it)<=8 } &&
        parameters.subList(168,176).all { it in .2..8.0 } && hidden.size==8 && hidden.all { it.isFinite() && abs(it)<=1.00001 } &&
        personality.size==5 && personality.all { it.isFinite() && it in 0.0..1.0 } && exposure.size==8 && exposure.all { it>=0 } &&
        learnedTick in -1..tick && novelty.isFinite() && novelty in 0.0..1.0 &&
        memory.valid(tick) && behaviors.all { b-> b.size==6 && b.all { it.isFinite() && it in 0.0..1.0 } }
    companion object {

        fun inherit(world: WorldState,citizen: Citizen,first: MindState,second: MindState): MindState {
            val rng=Canonical.stream(world.seed,citizen.id,world.tick,"ltc-family-inherit-v1")
            val parameters=List(248) { index ->
                val blended=(first.parameters[index]+second.parameters[index])*.5+(rng.nextDouble()-.5)*.02
                if(index in 168..175) blended.coerceIn(.2,8.0) else blended.coerceIn(-8.0,8.0)
            }
            val hidden=List(8) { index ->
                ((first.hidden[index]+second.hidden[index])*.5+(rng.nextDouble()-.5)*.01).coerceIn(-1.0,1.0)
            }
            val personality=listOf(
                citizen.genome.curiosity.express(),
                (first.personality[1]+second.personality[1])*.5,
                citizen.genome.sociability.express(),
                (first.personality[3]+second.personality[3])*.5,
                (first.personality[4]+second.personality[4])*.5
            ).map { it.coerceIn(0.0,1.0) }
            val exposure=List(8) { index ->
                ((first.exposure[index].toLong()+second.exposure[index].toLong())/2).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            }
            val inheritedMemory=first.memory.learnFrom(citizen.id,second.memory,world.tick)
            val behaviors=(first.behaviors+second.behaviors).distinct().takeLast(64)
            return MindState(
                parameters=parameters,
                hidden=hidden,
                personality=personality,
                exposure=exposure,
                learnedTick=-1,
                novelty=(first.novelty+second.novelty)*.5,
                behaviors=behaviors,
                memory=inheritedMemory
            ).also { check(it.valid(world.tick)) }
        }
        fun initial(world: WorldState,citizen: Citizen): MindState {
            val rng=Canonical.stream(world.seed,citizen.id,0,"ltc-genesis-v1")
            val p=MutableList(248) { (rng.nextDouble()-.5)*.6 }
            repeat(8) { i->p[168+i]=.8+rng.nextDouble()*1.5; p[176+i]=rng.nextDouble()*1.6-.8 }
            return MindState(p,List(8) { 0.0 },listOf(citizen.genome.curiosity.express(),rng.nextDouble(),citizen.genome.sociability.express(),rng.nextDouble(),rng.nextDouble()))
        }
    }
}
@OptIn(ExperimentalSerializationApi::class)
@Serializable data class CognitiveFrame(val sourceTick: Long,val sourceHash: String,
    val minds: Map<String,MindState>,val decisions: Map<String,Activity>,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val evolution: PolicyRevision? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val lesson: KnowledgeLesson? = null) {
    fun snapshot(): CognitiveFrame = copy(minds=minds.mapValues { (_,m)-> m.copy(
        parameters=m.parameters.toList(),hidden=m.hidden.toList(),personality=m.personality.toList(),
        exposure=m.exposure.toList(),behaviors=m.behaviors.map { it.toList() },memory=m.memory.snapshot()) },decisions=decisions.toMap(),evolution=evolution?.snapshot())
    fun applicable(world: WorldState): Boolean {
        val ids=world.agents.map { it.id }.toSet()
        return sourceTick==world.tick && sourceHash==Canonical.hash(Canonical.state(world)) && minds.keys==ids &&
            (evolution==null || evolution.applicable(world)) && (lesson==null || lesson.applicable(world)) && decisions.keys==ids && minds.values.all { it.valid(world.tick) }
    }
}
