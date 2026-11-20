package com.enhikyy.girassol.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlin.math.*

@Serializable enum class AttachmentPattern { SECURE, ANXIOUS, AVOIDANT, DISORGANIZED }

/** Deterministic affective, somatic and oscillatory state serialized with each citizen's mind. */
@Serializable data class SoulModel(
    val ageYears: Double = 0.0,
    val biologicalHealth: Double = 1.0,
    val attachment: AttachmentPattern = AttachmentPattern.SECURE,
    val bigFive: List<Double> = List(5) { .5 },
    val cortisol: Double = .25,
    val oxytocin: Double = .45,
    val dopamine: Double = .5,
    val pleasure: Double = .1,
    val arousal: Double = .2,
    val dominance: Double = 0.0,
    val expectedSensory: List<Double> = List(16) { 0.0 },
    val lastPredictionError: Double = 0.0,
    val kuramotoPhase: Double = 0.0,
    val kuramotoNaturalFrequency: Double = 1.0,
    val ignitionIndex: Double = 0.0,
    val initialized: Boolean = false
) {
    fun valid(): Boolean =
        ageYears.isFinite() && ageYears in 0.0..120.0 &&
        biologicalHealth.isFinite() && biologicalHealth in 0.0..1.0 &&
        bigFive.size == 5 && bigFive.all { it.isFinite() && it in 0.0..1.0 } &&
        listOf(cortisol, oxytocin, dopamine).all { it.isFinite() && it in 0.0..1.0 } &&
        listOf(pleasure, arousal, dominance).all { it.isFinite() && it in -1.0..1.0 } &&
        expectedSensory.size == 16 && expectedSensory.all { it.isFinite() && it in 0.0..1.0 } &&
        lastPredictionError.isFinite() && lastPredictionError in 0.0..1.0 &&
        kuramotoPhase.isFinite() && kuramotoNaturalFrequency.isFinite() && kuramotoNaturalFrequency in .25..4.0 &&
        ignitionIndex.isFinite() && ignitionIndex in 0.0..1.0

    companion object {
        fun initial(world: WorldState, citizen: Citizen): SoulModel {
            val rng = Canonical.stream(world.seed, citizen.id, world.tick, "anokhin-human-soul-genesis-v1")
            val socialTrust = world.ties.filter { it.from == citizen.id }.map { it.affinity }.average().let { if (it.isFinite()) it else .45 }
            val attachment = when {
                socialTrust >= .62 -> AttachmentPattern.SECURE
                citizen.needs.social < .35 && socialTrust < .4 -> AttachmentPattern.AVOIDANT
                citizen.needs.social < .35 -> AttachmentPattern.ANXIOUS
                else -> AttachmentPattern.DISORGANIZED
            }
            val age = if (world.genealogy.any { it.child == citizen.id }) 0.0 else 20.0 + rng.nextDouble() * 25.0
            val personality = world.minds[citizen.id]?.personality ?: listOf(
                citizen.genome.curiosity.express(), rng.nextDouble(),
                citizen.genome.sociability.express(), rng.nextDouble(), rng.nextDouble()
            )
            return SoulModel(
                ageYears = age,
                biologicalHealth = .82 + rng.nextDouble() * .18,
                attachment = attachment,
                bigFive = personality.map { it.coerceIn(0.0, 1.0) },
                kuramotoPhase = rng.nextDouble() * Math.PI * 2.0 - Math.PI,
                kuramotoNaturalFrequency = .85 + rng.nextDouble() * .3
            )
        }

        fun inherit(world: WorldState, child: Citizen, first: SoulModel, second: SoulModel): SoulModel {
            val rng = Canonical.stream(world.seed, child.id, world.tick, "anokhin-soul-inheritance-v1")
            val parentsTrust = (first.oxytocin + second.oxytocin) / 2.0
            return SoulModel(
                ageYears = 0.0,
                biologicalHealth = ((first.biologicalHealth + second.biologicalHealth) / 2.0 + (rng.nextDouble() - .5) * .04).coerceIn(0.0, 1.0),
                attachment = when {
                    parentsTrust >= .65 -> AttachmentPattern.SECURE
                    parentsTrust <= .3 -> AttachmentPattern.AVOIDANT
                    else -> AttachmentPattern.ANXIOUS
                },
                bigFive = List(5) { index ->
                    ((first.bigFive[index] + second.bigFive[index]) / 2.0 + (rng.nextDouble() - .5) * .06).coerceIn(0.0, 1.0)
                },
                cortisol = ((first.cortisol + second.cortisol) / 2.0).coerceIn(0.0, 1.0),
                oxytocin = ((first.oxytocin + second.oxytocin) / 2.0).coerceIn(0.0, 1.0),
                dopamine = ((first.dopamine + second.dopamine) / 2.0).coerceIn(0.0, 1.0),
                pleasure = ((first.pleasure + second.pleasure) / 2.0).coerceIn(-1.0, 1.0),
                arousal = ((first.arousal + second.arousal) / 2.0).coerceIn(-1.0, 1.0),
                dominance = ((first.dominance + second.dominance) / 2.0).coerceIn(-1.0, 1.0),
                kuramotoPhase = (first.kuramotoPhase + second.kuramotoPhase) / 2.0,
                kuramotoNaturalFrequency = ((first.kuramotoNaturalFrequency + second.kuramotoNaturalFrequency) / 2.0).coerceIn(.25, 4.0)
            )
        }
    }
}

/** Serialized learned state, distinct from inherited loci. Native output is journal input. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable data class MindState @JvmOverloads constructor(val parameters: List<Double>,val hidden: List<Double>,
    val personality: List<Double>,val exposure: List<Int> = List(8) { 0 },
    val learnedTick: Long = -1,val novelty: Double = 0.0,val behaviors: List<List<Double>> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val memory: MemoryBank = MemoryBank(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val psyche: SoulModel = SoulModel(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val tsetlin: TsetlinState = TsetlinState(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val decision: DecisionTrace = DecisionTrace()) {
    fun valid(tick: Long): Boolean = parameters.size==248 && parameters.all { it.isFinite() && abs(it)<=8 } &&
        parameters.subList(168,176).all { it in .2..8.0 } && hidden.size==8 && hidden.all { it.isFinite() && abs(it)<=1.00001 } &&
        personality.size==5 && personality.all { it.isFinite() && it in 0.0..1.0 } && exposure.size==8 && exposure.all { it>=0 } &&
        learnedTick in -1..tick && novelty.isFinite() && novelty in 0.0..1.0 &&
        memory.valid(tick) && tsetlin.valid(tick) && decision.valid() && psyche.valid() && behaviors.all { b-> b.size==6 && b.all { it.isFinite() && it in 0.0..1.0 } }
    companion object {

        fun inherit(world: WorldState,citizen: Citizen,firstId: String,secondId: String,first: MindState,second: MindState): MindState {
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
            val inheritedProcedures=(first.memory.procedures.map { it.copy(teacher=firstId,lastTick=world.tick) } +
                second.memory.procedures.map { it.copy(teacher=secondId,lastTick=world.tick) })
                .groupBy { it.action }.values.map { group -> group.maxBy { it.successes } }
                .sortedBy { it.action.ordinal }
            val inheritedReflections=(first.memory.reflections+second.memory.reflections)
                .groupBy { it.action }.values.map { group -> group.maxBy { it.observations } }
                .sortedBy { it.action.ordinal }
            val inheritedMemory=MemoryBank(reflections=inheritedReflections,procedures=inheritedProcedures,reflectedTick=world.tick)
            val behaviors=(first.behaviors+second.behaviors).distinct().takeLast(64)
            return MindState(
                parameters=parameters,
                hidden=hidden,
                personality=personality,
                exposure=exposure,
                learnedTick=-1,
                novelty=(first.novelty+second.novelty)*.5,
                behaviors=behaviors,
                memory=inheritedMemory,
                psyche=SoulModel.inherit(world,citizen,first.psyche,second.psyche)
            ).also { check(it.valid(world.tick)) }
        }
        fun initial(world: WorldState,citizen: Citizen): MindState {
            val rng=Canonical.stream(world.seed,citizen.id,0,"ltc-genesis-v1")
            val p=MutableList(248) { (rng.nextDouble()-.5)*.6 }
            repeat(8) { i->p[168+i]=.8+rng.nextDouble()*1.5; p[176+i]=rng.nextDouble()*1.6-.8 }
            return MindState(
                p,
                List(8) { 0.0 },
                listOf(citizen.genome.curiosity.express(),rng.nextDouble(),citizen.genome.sociability.express(),rng.nextDouble(),rng.nextDouble()),
                psyche=SoulModel.initial(world,citizen)
            )
        }
    }
}
@OptIn(ExperimentalSerializationApi::class)
@Serializable data class CognitiveFrame(val sourceTick: Long,val sourceHash: String,
    val minds: Map<String,MindState>,val decisions: Map<String,Activity>,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val evolution: PolicyRevision? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val lesson: KnowledgeLesson? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val socialSynchrony: Double = 0.0,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val innerSpeech: Map<String,String> = emptyMap(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val economyEnabled: Boolean = false,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val immunityEnabled: Boolean = false,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val societyEnabled: Boolean = false) {
    fun snapshot(): CognitiveFrame = copy(minds=minds.mapValues { (_,m)-> m.copy(
        parameters=m.parameters.toList(),hidden=m.hidden.toList(),personality=m.personality.toList(),
        decision=m.decision.copy(topThree=m.decision.topThree.toList(),freeEnergy=m.decision.freeEnergy.toList(),expectedFreeEnergy=m.decision.expectedFreeEnergy.toList(),successProbability=m.decision.successProbability.toList()),
        exposure=m.exposure.toList(),behaviors=m.behaviors.map { it.toList() },memory=m.memory.snapshot(),
        psyche=m.psyche.copy(bigFive=m.psyche.bigFive.toList(),expectedSensory=m.psyche.expectedSensory.toList())) },
        decisions=decisions.toMap(),evolution=evolution?.snapshot(),innerSpeech=innerSpeech.toMap())
    fun applicable(world: WorldState): Boolean {
        val ids=world.agents.map { it.id }.toSet()
        return (!societyEnabled || economyEnabled) && sourceTick==world.tick && sourceHash==Canonical.hash(Canonical.state(world)) && minds.keys==ids &&
            socialSynchrony.isFinite() && socialSynchrony in 0.0..1.0 &&
            innerSpeech.keys.all { it in ids } && innerSpeech.values.all { text -> text.isNotBlank() && text.length<=240 && text.none { it.isISOControl() } } &&
            (evolution==null || evolution.applicable(world)) && (lesson==null || (lesson.applicable(world) && (!immunityEnabled ||
                minds[lesson.researcher]?.memory?.opinions?.any { p -> p.claim==lesson.rowHash && p.root==lesson.acquisitionHash &&
                    p.sourceAgent==lesson.verifier && p.sourceTick==lesson.originTick && p.sourceEventHash==lesson.originEventHash &&
                    p.domain==lesson.domain && p.raw==SubjectiveOpinion(lesson.confidence/100.0,0.0,1-lesson.confidence/100.0) &&
                    abs(p.trust-ProvenanceImmunity.trust(world,lesson.researcher,lesson.verifier,lesson.domain))<1e-12 }==true))) && decisions.keys==ids && minds.values.all { it.valid(world.tick) }
    }
}
