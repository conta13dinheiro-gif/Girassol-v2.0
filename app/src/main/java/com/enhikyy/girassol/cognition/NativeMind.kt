package com.enhikyy.girassol.cognition

import com.enhikyy.girassol.core.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/** Called only by the service's world thread; all learned parameters enter its canonical event. */
internal object NativeMind {
    init { com.enhikyy.girassol.cognition.GeaNativeBridge.requireAvailable() }
    private external fun advance(data: ByteBuffer): Boolean
    private val networkBuffer=ThreadLocal.withInitial { ByteBuffer.allocateDirect(280*8).order(ByteOrder.nativeOrder()) }
    /** One actual Hebbian/LTC study update. The exact parameter diff is later journalled by WorldActor. */
    fun study(old: MindState,text: String,confidence: Int,tick: Long): MindState {
        require(text.isNotBlank() && confidence in 90..100)
        val data=checkNotNull(networkBuffer.get()).apply { clear() }
        old.parameters.forEach(data::putDouble);old.hidden.forEach(data::putDouble)
        // Content-addressed stimulus is a neural exposure, not a claim of language understanding.
        val digest=java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        repeat(12) { data.putDouble((digest[it].toInt() and 255)/255.0) }
        data.putDouble(confidence/100.0).putDouble(1.0).putDouble(Activity.STUDY.ordinal.toDouble()).putDouble(1.0)
        repeat(8) { data.putDouble(0.0) }
        check(advance(data))
        val exposure=old.exposure.toMutableList();if(exposure[Activity.STUDY.ordinal]<Int.MAX_VALUE) exposure[Activity.STUDY.ordinal]++
        return old.copy(parameters=List(248) { data.getDouble(it*8) },hidden=List(8) { data.getDouble((248+it)*8) },exposure=exposure,learnedTick=tick)
            .also { check(it.valid(tick)) }
    }
    /** Both isolated knowledge experiments and live assimilation use this same admission gate. */
    fun assimilate(world: WorldState,recipient: String,old: MindState,evidence: CognitiveEvidence,text: String): MindState? {
        val bank=ProvenanceImmunity.admit(world,recipient,old.memory,evidence) ?: return null
        return study(old.copy(memory=bank),text,evidence.confidence,world.tick)
    }
    private fun ripple(old: MindState,world: WorldState,citizen: Citizen,tier: ThermalTier): MindState {
        if(tier!=ThermalTier.NONE_OR_LIGHT || !citizen.acting || citizen.activity!=Activity.SLEEP ||
            world.tick-(old.memory.ripple?.tick ?: -30L)<30) return old
        val ids=HippocampalSleepConsolidator.consolidate(old.memory,world.tick).report.selectedReplayEventIds.toSet()
        val selected=old.memory.episodes.filter { episode ->
            val magnitude=episode.embedding[episode.action.ordinal]
            episode.eventId in ids && magnitude>1e-6 && (10..14).all { episode.embedding[it]>=0 && episode.embedding[it]<=magnitude+1e-6 }
        }.sortedBy { it.eventId }.take(12)
        if(selected.isEmpty()) return old
        val before=Canonical.hash(old.parameters.joinToString(","))
        var weights=old.parameters;var hidden=old.hidden
        val data=checkNotNull(networkBuffer.get())
        for(episode in selected) {
            // Recover the original structured needs from the normalized one-hot observation.
            val scale=episode.embedding[episode.action.ordinal].toDouble()
            check(scale>0)
            fun need(index: Int)=(episode.embedding[index]/scale).coerceIn(0.0,1.0)
            val inputs=listOf(1-need(11),1-need(10),1-need(12),1-need(13),1-need(14))+old.personality+listOf(citizen.skill.mastery,episode.importance)
            data.clear();weights.forEach(data::putDouble);hidden.forEach(data::putDouble);inputs.forEach(data::putDouble)
            data.putDouble(episode.reward).putDouble(episode.importance).putDouble(episode.action.ordinal.toDouble()).putDouble(1.0)
            repeat(8) { data.putDouble(0.0) };check(advance(data)) { "Falha no replay SWR" }
            weights=List(248) { data.getDouble(it*8) };hidden=List(8) { data.getDouble((248+it)*8) }
        }
        val after=Canonical.hash(weights.joinToString(","))
        val bank=AssociativeCognitiveMemory.consolidate(old.memory,selected,world.tick,before,after)
        // Replay changes synaptic parameters; awake activation and external action scores keep their ownership.
        return old.copy(parameters=weights,memory=bank)
    }
    @JvmOverloads fun evaluate(
        world: WorldState,
        lastActions: List<ActionRecord> = emptyList(),
        policy: List<Double> = world.evolution?.weights ?: List(8) { 0.0 },
        thermalTier: ThermalTier = ThermalTier.NONE_OR_LIGHT,
        genome: CognitivePolicyGenome = world.evolution?.cognitive?.candidate ?: CognitivePolicyGenome()
    ): CognitiveFrame {
        require(genome.valid())
        require(policy.size==8 && policy.all { it.isFinite() && it in -2.0..2.0 })
        val sourceHash=Canonical.hash(Canonical.state(world))
        val institutionalWorld=world.copy(tick=world.tick+1)
        val institutionalRules=world.society?.rules ?: SocietyAdicoCliodynamicsEngine.foundingRules()
        val prepared=world.agents.associate { citizen ->
            val mind=world.minds[citizen.id] ?: run {
                val family=world.genealogy.lastOrNull { it.child==citizen.id }
                val first=family?.let { world.minds[it.parentOne] }
                val second=family?.let { world.minds[it.parentTwo] }
                if(first!=null && second!=null) MindState.inherit(world,citizen,family.parentOne,family.parentTwo,first,second)
                else MindState.initial(world,citizen)
            }
            citizen.id to mind
        }
        val replayAgents=world.agents.filter { it.acting && it.activity==Activity.SLEEP && prepared.getValue(it.id).memory.episodes.isNotEmpty() &&
            world.tick-(prepared.getValue(it.id).memory.ripple?.tick ?: -30L)>=30 }
            .sortedWith(compareBy<Citizen> { prepared.getValue(it.id).memory.ripple?.tick ?: -30L }.thenBy { it.id }).take(2).map { it.id }.toSet()
        val autonomy=NativeAutonomy.evaluate(world,lastActions,prepared)
        val minds=linkedMapOf<String,MindState>(); val decisions=linkedMapOf<String,Activity>()
        val qdView=world.qualityDiversity?.view()
        val populationSemantics=PopulationSemantics.measure(world)
        val qdCandidates=ArrayList<NativeQualityDiversity.Candidate>(world.agents.size)
        world.agents.forEach { citizen->
            val prior=prepared.getValue(citizen.id)
            val outcome=lastActions.firstOrNull { it.agentId==citizen.id }?.outcome
            val old=PedagogyAndDiploidEvolutionEngine.symbiogenesis(world,citizen,prior,outcome,sourceHash).copy(psyche=autonomy.psyches.getValue(citizen.id))
            val memory=citizen.memories.lastOrNull()?.takeIf { it.tick==world.tick && it.tick>old.learnedTick }
            val learn=world.tick>old.learnedTick && (memory!=null || outcome=="PRECONDITION_FAILED" || outcome=="RESERVED")
            val reward=memory?.reward ?: if(outcome=="PRECONDITION_FAILED") -.15 else -.03
            val behavior=listOf(citizen.activity.ordinal/7.0,(citizen.x/100).coerceIn(0.0,1.0),(citizen.z/100).coerceIn(0.0,1.0),citizen.needs.social,citizen.skill.mastery,if(outcome=="PRECONDITION_FAILED") 0.0 else if(outcome=="RESERVED") .5 else 1.0)
            val distances=old.behaviors.map { b->sqrt(b.zip(behavior).sumOf { (a,v)->(a-v).pow(2) }/6) }.sorted()
            val novelty=if(distances.isEmpty()) 1.0 else (distances.take(5).average()*3).coerceIn(0.0,1.0)
            val n=citizen.needs
            val features=listOf(1-n.hydration,1-n.energy,1-n.glucose,1-n.social,1-n.curiosity)+old.personality+listOf(citizen.skill.mastery,novelty)
            val data=checkNotNull(networkBuffer.get()).apply { clear() }
            old.parameters.forEach(data::putDouble); old.hidden.forEach(data::putDouble); features.forEach(data::putDouble)
            data.putDouble(if(learn) reward else 0.0).putDouble(novelty).putDouble(citizen.activity.ordinal.toDouble()).putDouble(if(learn) 1.0 else 0.0)
            repeat(8) { data.putDouble(0.0) }
            check(advance(data)) { "Falha na atualização LTC" }
            fun values(start: Int,count: Int)=List(count) { data.getDouble((start+it)*8) }
            var nativeScores=values(272,8)
            val exposure=old.exposure.toMutableList()
            if(learn && exposure[citizen.activity.ordinal]<Int.MAX_VALUE) exposure[citizen.activity.ordinal]++
            val discovered=learn && (distances.firstOrNull() ?: 1.0)>.06
            val archive=(if(discovered) old.behaviors+listOf(behavior) else old.behaviors).takeLast(256)
            val admissions=(old.discoveredBehaviors+if(discovered) 1 else 0).coerceAtMost(1_000_000_000_000L)
            var bank=old.memory.observe(world,citizen,lastActions.firstOrNull { it.agentId==citizen.id },genome.memRlLearningRate,genome.actRDecayExponent,true,sourceHash)
            if(citizen.acting && citizen.activity==Activity.TALK && outcome=="OK") {
                val contact=world.society?.contacts?.firstOrNull { it.from==citizen.id && it.tick==world.tick }
                val teacher=if(world.society!=null) world.agents.firstOrNull { it.id==contact?.peer }
                    else world.agents.filter { it.id!=citizen.id && hypot(it.x-citizen.x,it.z-citizen.z)<=2.5 }.minByOrNull { it.id }
                if(teacher!=null) world.minds[teacher.id]?.let {
                    bank=VoyagerSkillLibrary.teach(world,citizen,bank,teacher,it.memory)
                    bank=ProvenanceImmunity.learnFrom(world,citizen.id,bank,it.memory)
                }
            }
            bank=AssociativeCognitiveMemory.connect(bank)
            val recalled=NativeMemory.recall(bank,citizen,world.tick)
            bank=bank.recordRecall(recalled.map { it.episode.eventId },world.tick)
            var next=old.copy(memory=bank,parameters=values(0,248),hidden=values(248,8),exposure=exposure,learnedTick=if(learn) world.tick else old.learnedTick,novelty=novelty,behaviors=archive,noveltyAdmissions=admissions)
            val adapted=NativeLora.evaluate(world,citizen,prior,next,thermalTier,sourceHash)
            next=next.copy(lora=adapted.state);nativeScores=adapted.scores
            if(citizen.id in replayAgents) next=ripple(next,world,citizen,thermalTier)
            bank=next.memory
            check(next.valid(world.tick))
            minds[citizen.id]=next
            val previousGate=old.tsetlin
            var gateBytes=previousGate.bytes()
            if(previousGate.decisionTick==world.tick-1 && previousGate.action==citizen.activity.ordinal &&
                outcome in listOf("OK","PRECONDITION_FAILED","RESERVED")) {
                gateBytes=GeaNativeBridge.tsetlinTrain(gateBytes,previousGate.features,if(outcome=="OK") 1 else 0,
                    Canonical.stream(world.seed,citizen.id,world.tick,"tsetlin-feedback-v1").nextLong())
            }
            val needs=listOf(1-n.hydration,1-n.energy,1-n.glucose,1-n.energy,1-n.social,1-n.curiosity,1-n.curiosity,1-n.curiosity)
            val exploration=Canonical.stream(world.seed,citizen.id,world.tick,"intrinsic-exploration-v1")
            val thermalCost=AllostaticThermalCoupling.computeNeuralDeliberationCost(thermalTier,genome.thermalThrottleSensitivity,n.energy)
            val policyScale=1.0/(1.0+thermalCost)
            val temperature=(.04+.20*old.personality[0]*(1-novelty))*policyScale*genome.epistemicExplorationWeight/.65
            val noise=List(8) { -ln(-ln(exploration.nextDouble().coerceIn(1e-12,1-1e-12)))*temperature }
            val decisionBank=bank
            val donorBias=qdView?.let { view ->
                view.actionBias(Canonical.stream(world.seed,citizen.id,world.tick,"qd-donor-v1").nextInt(view.occupied))
            } ?: List(8) { 0.0 }
            // nativeScores was copied before SWR reused the fixed JNI buffer.
            val scores=PianoWorkspace.aggregate(
                { Activity.entries.map { activity ->
                    val i=activity.ordinal
                    needs[i]*1.7+SemanticCollapseGuard.noveltyEpistemicBonus(activity.name,world.actionCounts)+PopulationSemantics.noveltyBonus(activity,populationSemantics)
                } },
                { Activity.entries.map { activity ->
                    val experience=recalled.filter { it.episode.action==activity }
                    val expected=if(experience.isEmpty()) decisionBank.semantic.firstOrNull { it.action==activity }?.expectedReward
                        ?: decisionBank.reflections.firstOrNull { it.action==activity }?.expectedReward ?: 0.0
                        else experience.sumOf { it.episode.reward*it.score }/experience.sumOf { it.score.toDouble() }.coerceAtLeast(.001)
                    expected*.4+(decisionBank.procedures.firstOrNull { it.action==activity }?.expectedReward ?: 0.0)*.15+
                        decisionBank.learningFor(activity).practiceBonus(world.tick)+decisionBank.workingMemoryBonus(activity,world.tick)
                } },
                { Activity.entries.map { activity ->
                    val psyche=old.psyche
                    val pressure=world.society?.pressure?.psi ?: 0.0
                    val opinion=world.society?.opinions?.get(citizen.id)?.value ?: 0.0
                    (if(activity==Activity.TALK) minOf(pressure,10.0)*.02+opinion*.03 else 0.0)+when(activity) {
                        Activity.STUDY -> psyche.dopamine*.12+(psyche.ignitionIndex-.65).coerceAtLeast(0.0)*.45
                        Activity.TALK -> psyche.oxytocin*.12+maxOf(0.0,psyche.pleasure)*.04
                        Activity.SLEEP -> psyche.cortisol*.06
                        Activity.BUILD -> -psyche.cortisol*.05
                        else -> 0.0
                    }
                } },
                { Activity.entries.map { activity ->
                    val room=SmartTerrain.target(world,citizen,activity)
                    var value=policy[activity.ordinal]*policyScale-hypot(room.x-citizen.x,room.z-citizen.z)*.025
                    if(activity==Activity.SLEEP && n.energy>.8) value-=1
                    val hasTool=world.economy?.tools?.any { it.agent==citizen.id && it.action==activity } ?: false
                    if(activity==Activity.BUILD && ((world.balances["market/timber"] ?: 0)<(if(hasTool) 4 else 5) || world.rooms.size>=134)) value-=3
                    if(activity==Activity.WRITE && ((world.balances["market/paper"] ?: 0)==0 || (!hasTool && (world.balances["market/timber"] ?: 0)==0))) value-=3
                    if(activity==Activity.EAT && (world.balances["${citizen.id}/coins"] ?: 0)==0 && (world.economy==null || (world.balances["${DoubleEntryEconomyAndStigmergy.COMMONS}/coins"] ?: 0)==0)) value-=3
                    value+=(world.economy?.pheromones?.get(activity.ordinal*144+DoubleEntryEconomyAndStigmergy.cell(citizen.x,citizen.z)) ?: 0.0)*.01
                    if(SocietyAdicoCliodynamicsEngine.admit(institutionalWorld,citizen,activity,institutionalRules).action!=activity) value-=.8
                    value+GeaNativeBridge.tsetlinVote(gateBytes,TsetlinState.features(citizen,activity),true).coerceIn(-4,4)*.05
                } },
                { List(8) { i -> nativeScores[i]+donorBias[i]+noise[i]+.4/sqrt(exposure[i]+1.0) } }
            )
            val likelihoods=Activity.entries.map {
                val gate=(GeaNativeBridge.tsetlinVote(gateBytes,TsetlinState.features(citizen,it),false)+9.0)/18.0
                val trace=decisionBank.learningFor(it)
                val item=trace.item
                if(item==null) gate else {
                    val recall=trace.retrievability(world.tick)
                    val forecast=trace.responseProbability()*recall+item.guessing*(1-recall)
                    (.75*gate+.25*forecast).coerceIn(.001,.999)
                }
            }
            val selection=ActiveInferencePianoEngine.select(citizen,scores,likelihoods,genome)
            val decision=selection.action
            decisions[citizen.id]=decision
            val expectedSensors=NativeAutonomy.predictNext(
                world,citizen,decision,autonomy.actualSensory.getValue(citizen.id)
            )
            minds[citizen.id]=minds.getValue(citizen.id).copy(
                decision=DecisionTrace(selection.topThree,selection.evidence.map { it.freeEnergy },
                    selection.evidence.map { it.expectedFreeEnergy },selection.evidence.map { it.success },
                    thermalTier==ThermalTier.NONE_OR_LIGHT && GeaNativeBridge.tsetlinVote(gateBytes,TsetlinState.features(citizen,decision),true)<4 &&
                        (1-minOf(n.energy,n.hydration,n.glucose)>=genome.cognitiveGateMinUrgency ||
                         abs(selection.evidence[selection.topThree[0].ordinal].expectedFreeEnergy-selection.evidence[selection.topThree[1].ordinal].expectedFreeEnergy)<=genome.cognitiveGateTieThreshold),nativeActionValues=nativeScores.map { it/8 }),
                tsetlin=TsetlinState(TsetlinState.encode(gateBytes),TsetlinState.features(citizen,decision),decision.ordinal,world.tick),
                psyche=minds.getValue(citizen.id).psyche.copy(expectedSensory=expectedSensors,initialized=true)
            )
            val job=world.economy?.jobs?.firstOrNull { it.agent==citizen.id }
            if(learn && outcome=="OK" && job?.phase=="DONE" && job.receiptTick==world.tick && old.decision.nativeActionValues.size==8) {
                qdCandidates+=NativeQualityDiversity.Candidate(citizen,minds.getValue(citizen.id),reward,old.decision.nativeActionValues)
            }
        }
        val qd=NativeQualityDiversity.update(world,qdCandidates)
        return CognitiveFrame(
            world.tick,sourceHash,minds,decisions,
            socialSynchrony=autonomy.orderParameter,economyEnabled=true,immunityEnabled=true,societyEnabled=true,pedagogyEnabled=true,qualityDiversity=qd,biologyEnabled=true
        )
    }
}
