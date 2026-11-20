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
    @JvmOverloads fun evaluate(
        world: WorldState,
        lastActions: List<ActionRecord> = emptyList(),
        policy: List<Double> = world.evolution?.weights ?: List(8) { 0.0 },
        thermalTier: ThermalTier = ThermalTier.NONE_OR_LIGHT,
        genome: CognitivePolicyGenome = world.evolution?.cognitive?.candidate ?: CognitivePolicyGenome()
    ): CognitiveFrame {
        require(genome.valid())
        require(policy.size==8 && policy.all { it.isFinite() && it in -2.0..2.0 })
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
        val autonomy=NativeAutonomy.evaluate(world,lastActions,prepared)
        val minds=linkedMapOf<String,MindState>(); val decisions=linkedMapOf<String,Activity>()
        world.agents.forEach { citizen->
            val old=prepared.getValue(citizen.id).copy(psyche=autonomy.psyches.getValue(citizen.id))
            val memory=citizen.memories.lastOrNull()?.takeIf { it.tick==world.tick && it.tick>old.learnedTick }
            val outcome=lastActions.firstOrNull { it.agentId==citizen.id }?.outcome
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
            val exposure=old.exposure.toMutableList()
            if(learn && exposure[citizen.activity.ordinal]<Int.MAX_VALUE) exposure[citizen.activity.ordinal]++
            val archive=if(learn && (distances.firstOrNull() ?: 1.0)>.06) old.behaviors+listOf(behavior) else old.behaviors
            var bank=old.memory.observe(world,citizen,lastActions.firstOrNull { it.agentId==citizen.id },genome.memRlLearningRate,genome.actRDecayExponent)
            if(citizen.acting && citizen.activity==Activity.TALK && outcome=="OK") {
                val teacher=world.agents.filter { it.id!=citizen.id && hypot(it.x-citizen.x,it.z-citizen.z)<=2.5 }.minByOrNull { it.id }
                if(teacher!=null) world.minds[teacher.id]?.let { bank=bank.learnFrom(teacher.id,it.memory,world.tick) }
            }
            val recalled=NativeMemory.recall(bank,citizen,world.tick)
            bank=bank.recordRecall(recalled.map { it.episode.eventId },world.tick)
            val next=old.copy(memory=bank,parameters=values(0,248),hidden=values(248,8),exposure=exposure,learnedTick=if(learn) world.tick else old.learnedTick,novelty=novelty,behaviors=archive)
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
            val nativeScores=values(272,8) // Workers never retain the reusable JNI buffer.
            val scores=PianoWorkspace.aggregate(
                { Activity.entries.map { activity ->
                    val i=activity.ordinal
                    needs[i]*1.7+SemanticCollapseGuard.noveltyEpistemicBonus(activity.name,world.actionCounts)
                } },
                { Activity.entries.map { activity ->
                    val experience=recalled.filter { it.episode.action==activity }
                    val expected=if(experience.isEmpty()) decisionBank.reflections.firstOrNull { it.action==activity }?.expectedReward ?: 0.0
                        else experience.sumOf { it.episode.reward*it.score }/experience.sumOf { it.score.toDouble() }.coerceAtLeast(.001)
                    expected*.4+(decisionBank.procedures.firstOrNull { it.action==activity }?.expectedReward ?: 0.0)*.15+
                        decisionBank.learningFor(activity).practiceBonus(world.tick)+decisionBank.workingMemoryBonus(activity,world.tick)
                } },
                { Activity.entries.map { activity ->
                    val psyche=old.psyche
                    when(activity) {
                        Activity.STUDY -> psyche.dopamine*.12+(psyche.ignitionIndex-.65).coerceAtLeast(0.0)*.45
                        Activity.TALK -> psyche.oxytocin*.12+maxOf(0.0,psyche.pleasure)*.04
                        Activity.SLEEP -> psyche.cortisol*.06
                        Activity.BUILD -> -psyche.cortisol*.05
                        else -> 0.0
                    }
                } },
                { Activity.entries.map { activity ->
                    val room=world.rooms.first { it.id==activity.room }
                    var value=policy[activity.ordinal]*policyScale-hypot(room.x-citizen.x,room.z-citizen.z)*.025
                    if(activity==Activity.SLEEP && n.energy>.8) value-=1
                    if(activity==Activity.BUILD && (world.balances["market/timber"] ?: 0)<4) value-=3
                    if(activity==Activity.WRITE && (world.balances["market/paper"] ?: 0)==0) value-=3
                    if(activity==Activity.EAT && (world.balances["${citizen.id}/coins"] ?: 0)==0) value-=3
                    value+GeaNativeBridge.tsetlinVote(gateBytes,TsetlinState.features(citizen,activity),true).coerceIn(-4,4)*.05
                } },
                { List(8) { i -> nativeScores[i]+noise[i]+.4/sqrt(exposure[i]+1.0) } }
            )
            val likelihoods=Activity.entries.map {
                (GeaNativeBridge.tsetlinVote(gateBytes,TsetlinState.features(citizen,it),false)+9.0)/18.0
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
                         abs(selection.evidence[selection.topThree[0].ordinal].expectedFreeEnergy-selection.evidence[selection.topThree[1].ordinal].expectedFreeEnergy)<=genome.cognitiveGateTieThreshold)),
                tsetlin=TsetlinState(TsetlinState.encode(gateBytes),TsetlinState.features(citizen,decision),decision.ordinal,world.tick),
                psyche=minds.getValue(citizen.id).psyche.copy(expectedSensory=expectedSensors,initialized=true)
            )
        }
        return CognitiveFrame(
            world.tick,Canonical.hash(Canonical.state(world)),minds,decisions,
            socialSynchrony=autonomy.orderParameter
        )
    }
}
