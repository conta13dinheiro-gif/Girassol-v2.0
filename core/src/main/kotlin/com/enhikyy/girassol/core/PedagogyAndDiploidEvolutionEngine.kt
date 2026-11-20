package com.enhikyy.girassol.core

import kotlinx.serialization.Serializable
import kotlin.math.*

/** Structured action-concepts, not language embeddings. Unknown citizens are excluded, not uniform. */
@Serializable data class PopulationSemanticState(val observed: Int,val samples: Int,val frequency: List<Double>,
    val entropy: Double,val betweenAgents: Double) {
    fun valid(population: Int)=observed in 0..population && samples in observed..population*32 && frequency.size==8 &&
        frequency.all { it.isFinite() && it in 0.0..1.0 } && entropy.isFinite() && entropy in 0.0..1.0 &&
        betweenAgents.isFinite() && betweenAgents in 0.0..1.0 &&
        abs(frequency.sum()-if(observed==0) 0.0 else 1.0)<1e-10 && (observed>1 || betweenAgents==0.0)
}
object PopulationSemantics {
    private fun entropy(profile: List<Double>)=profile.filter { it>0 }.sumOf { -it*ln(it) }
    /** Generalized Jensen-Shannon / ln(8): invariant to duplicating the whole population. */
    fun divergence(profiles: List<List<Double>>): Double {
        require(profiles.size in 1..150 && profiles.all { p -> p.size==8 && p.all { it.isFinite() && it in 0.0..1.0 } && abs(p.sum()-1)<1e-10 })
        val mean=List(8) { i -> profiles.sumOf { it[i] }/profiles.size }
        return ((entropy(mean)-profiles.sumOf(::entropy)/profiles.size)/ln(8.0)).coerceIn(0.0,1.0)
    }
    fun measure(world: WorldState): PopulationSemanticState {
        require(world.agents.size in 1..150)
        val total=IntArray(8);var samples=0
        val profiles=world.agents.mapNotNull { citizen ->
            val recent=citizen.memories.takeLast(32)
            if(recent.isEmpty()) null else {
                val counts=IntArray(8);recent.forEach { counts[it.action.ordinal]++;total[it.action.ordinal]++;samples++ }
                List(8) { counts[it].toDouble()/recent.size }
            }
        }
        val frequency=List(8) { if(samples==0) 0.0 else total[it].toDouble()/samples }
        val h=frequency.filter { it>0 }.sumOf { -it*ln(it) }/ln(8.0)
        return PopulationSemanticState(profiles.size,samples,frequency,h.coerceIn(0.0,1.0),if(profiles.isEmpty()) 0.0 else divergence(profiles))
    }
    fun noveltyBonus(action: Activity,state: PopulationSemanticState): Double =
        if(state.observed==0 || state.entropy>=.55) 0.0 else .1*(.55-state.entropy)/.55*(1-state.frequency[action.ordinal])
}

@Serializable data class BaldwinTrial(val first: String,val second: String,val tick: Long,val firstLearning: Double,
    val secondLearning: Double,val probability: Double,val draw: Double,val admitted: Boolean) {
    fun valid(world: WorldState): Boolean {
        if(first>=second || tick!=world.tick || world.agents.none { it.id==first } || world.agents.none { it.id==second } ||
            listOf(firstLearning,secondLearning,probability,draw).any { !it.isFinite() || it !in 0.0..1.0 }) return false
        val expected=.25+.75*(firstLearning+secondLearning)/2
        val random=Canonical.stream(world.seed,"$first:$second",tick,"baldwin-selection-v1").nextDouble()
        return abs(probability-expected)<1e-12 && draw==random && admitted==(draw<probability) &&
            abs(firstLearning-PedagogyAndDiploidEvolutionEngine.learningFitness(world.minds[first]))<1e-12 &&
            abs(secondLearning-PedagogyAndDiploidEvolutionEngine.learningFitness(world.minds[second]))<1e-12 &&
            admitted==world.genealogy.any { it.parentOne==first && it.parentTwo==second && it.tick==tick }
    }
}
@Serializable data class BiologicalState(val tick: Long,val semantics: PopulationSemanticState,val selections: List<BaldwinTrial>) {
    fun valid(world: WorldState)=tick==world.tick && world.agents.size<=150 && semantics.valid(world.agents.size) && semantics==PopulationSemantics.measure(world) &&
        selections.size<=150 && selections.map { it.first to it.second }.distinct().size==selections.size && selections.all { it.valid(world) }
}

/** Learned functional modules are horizontal acquisitions; germline values and dominance stay intact. */
@Serializable data class SymbioticModule(val action: Activity,val donor: String,val tick: Long,val sourceHash: String,
    val donorGenomeHash: String,val donorBlockHash: String,val beforeHash: String,val afterHash: String,val trust: Double,
    val recipientKnowledge: Double,val donorKnowledge: Double,val donorExecutions: Int) {
    fun valid(now: Long)=donor.isNotBlank() && donor.length<=128 && tick in 1..now &&
        listOf(sourceHash,donorGenomeHash,donorBlockHash,beforeHash,afterHash).all { it.matches(Regex("[a-f0-9]{64}")) } &&
        trust.isFinite() && trust in .7..1.0 && recipientKnowledge.isFinite() && recipientKnowledge in 0.0..1.0 &&
        donorKnowledge.isFinite() && donorKnowledge in .75..1.0 && donorKnowledge>=recipientKnowledge+.05 && donorExecutions>=3
}

/** Baldwin selection acts on acquired competence. Meiosis retains the existing v8 50/50 segregation. */
class PedagogyAndDiploidEvolutionEngine(private val before: WorldState,private val minds: Map<String,MindState>) {
    private val trials=ArrayList<BaldwinTrial>(before.agents.size)
    fun admit(first: Citizen,second: Citizen,tick: Long): Boolean {
        require(first.id<second.id && tick==before.tick+1 && trials.size<150 && trials.none { it.first==first.id && it.second==second.id })
        val one=learningFitness(minds[first.id]);val two=learningFitness(minds[second.id])
        val probability=.25+.75*(one+two)/2
        val draw=Canonical.stream(before.seed,"${first.id}:${second.id}",tick,"baldwin-selection-v1").nextDouble()
        return (draw<probability).also { trials+=BaldwinTrial(first.id,second.id,tick,one,two,probability,draw,it) }
    }
    fun snapshot(next: WorldState)=BiologicalState(next.tick,PopulationSemantics.measure(next),trials.toList())
    companion object {
        /** Fixed eight-domain denominator rewards breadth; more verified practice approaches its bound. */
        fun learningFitness(mind: MindState?): Double = (mind?.memory?.learning.orEmpty().sumOf { trace ->
            if(trace.verifiedExecutions<3 || trace.knowledgeProbability<.75) 0.0 else {
                val n=trace.verifiedExecutions.toDouble();trace.knowledgeProbability*n/(n+8)
            }
        }/8).coerceIn(0.0,1.0)
        fun blockHash(parameters: List<Double>,action: Activity)=Canonical.hash(parameters.subList(184+action.ordinal*8,192+action.ordinal*8).joinToString(","))
        fun symbiogenesis(world: WorldState,citizen: Citizen,old: MindState,outcome: String?,sourceHash: String): MindState {
            if(world.tick<1 || !citizen.acting || citizen.activity!=Activity.TALK || outcome!="OK") return old
            val contact=world.society?.contacts?.firstOrNull { it.from==citizen.id && it.tick==world.tick } ?: return old
            val donor=world.agents.firstOrNull { it.id==contact.peer && it.lineage!=citizen.lineage } ?: return old
            val donorMind=world.minds[donor.id] ?: return old
            val reverse=world.ties.firstOrNull { it.from==donor.id && it.to==citizen.id } ?: return old
            val selected=donorMind.memory.skills.filter { skill ->
                val trace=donorMind.memory.learningFor(skill.action)
                val prior=old.memory.learningFor(skill.action)
                val last=old.symbioticModules.firstOrNull { it.action==skill.action }
                val reciprocal=reverse.domains[skill.preconditions.domain]?.mean ?: reverse.trust
                trace.verifiedExecutions>=3 && trace.knowledgeProbability>=.75 && trace.knowledgeProbability>=prior.knowledgeProbability+.05 &&
                    (last==null || world.tick-last.tick>=120) && reciprocal>=.7 &&
                    ProvenanceImmunity.trust(world,citizen.id,donor.id,skill.preconditions.domain)>=.7
            }.sortedWith(compareByDescending<VoyagerSkill> { donorMind.memory.learningFor(it.action).knowledgeProbability-old.memory.learningFor(it.action).knowledgeProbability }.thenBy { it.action.ordinal }).firstOrNull() ?: return old
            val action=selected.action;val trust=ProvenanceImmunity.trust(world,citizen.id,donor.id,selected.preconditions.domain)
            val parameters=old.parameters.toMutableList();val start=184+action.ordinal*8;val blend=.25*trust
            repeat(8) { i -> parameters[start+i]=((1-blend)*parameters[start+i]+blend*donorMind.parameters[start+i]).coerceIn(-8.0,8.0) }
            val trace=donorMind.memory.learningFor(action)
            val record=SymbioticModule(action,donor.id,world.tick,sourceHash,Canonical.genomeHash(donor.genome),blockHash(donorMind.parameters,action),
                blockHash(old.parameters,action),blockHash(parameters,action),trust,old.memory.learningFor(action).knowledgeProbability,trace.knowledgeProbability,trace.verifiedExecutions)
            return old.copy(parameters=parameters,symbioticModules=(old.symbioticModules.filter { it.action!=action }+record).sortedBy { it.action.ordinal })
        }
        fun validModules(items: List<SymbioticModule>,tick: Long)=items.size<=8 && items.map { it.action }.distinct().size==items.size && items.all { it.valid(tick) }
    }
}
