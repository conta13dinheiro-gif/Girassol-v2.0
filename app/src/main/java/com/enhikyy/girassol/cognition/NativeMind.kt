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
    fun evaluate(
        world: WorldState,
        lastActions: List<ActionRecord> = emptyList(),
        policy: List<Double> = world.evolution?.weights ?: List(8) { 0.0 },
        thermalTier: ThermalTier = ThermalTier.NONE_OR_LIGHT
    ): CognitiveFrame {
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
        val socialGraphIndex = SocietyAdicoCliodynamicsEngine.buildGraphIndex(world)
        val autonomy=NativeAutonomy.evaluate(world,lastActions,prepared)
        val minds=linkedMapOf<String,MindState>(); val decisions=linkedMapOf<String,Activity>()
        var sleepReplayBudget=when {
            world.agents.size>=100 -> 24
            world.agents.size>=50 -> 32
            world.agents.size>=16 -> 48
            else -> 72
        }
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
            val thermalCost = AllostaticThermalCoupling.computeNeuralDeliberationCost(thermalTier, 1.0, n.energy)
            val policyScale = 1.0 / (1.0 + thermalCost)
            val features=listOf(1-n.hydration,1-n.energy,1-n.glucose,1-n.social,1-n.curiosity)+old.personality+listOf(citizen.skill.mastery,novelty)
            val reflexFeatures=ByteArray(16)
            for(index in 0 until 5) reflexFeatures[index]=if(features[index]>=.5) 1 else 0
            for(index in 0 until 3) reflexFeatures[5+index]=if(old.personality[index]>=.5) 1 else 0
            reflexFeatures[8+citizen.activity.ordinal]=1
            val reflexVote=TsetlinReflexEngine.evaluate(citizen.id,reflexFeatures,outcome)
            val data=checkNotNull(networkBuffer.get()).apply { clear() }
            old.parameters.forEach(data::putDouble); old.hidden.forEach(data::putDouble); features.forEach(data::putDouble)
            data.putDouble(if(learn) reward else 0.0).putDouble(novelty).putDouble(citizen.activity.ordinal.toDouble()).putDouble(if(learn) 1.0 else 0.0)
            repeat(8) { data.putDouble(0.0) }
            check(advance(data)) { "Falha na atualização LTC" }
            fun values(start: Int,count: Int)=List(count) { data.getDouble((start+it)*8) }
            val decisionScores=values(272,8)
            var learnedParameters=values(0,248)
            var learnedHidden=values(248,8)
            val exposure=old.exposure.toMutableList()
            if(learn && exposure[citizen.activity.ordinal]<Int.MAX_VALUE) exposure[citizen.activity.ordinal]++
            val archive=if(learn && (distances.firstOrNull() ?: 1.0)>.06) old.behaviors+listOf(behavior) else old.behaviors
            var bank=old.memory.observe(world,citizen,lastActions.firstOrNull { it.agentId==citizen.id })
            if(citizen.acting && citizen.activity==Activity.TALK && outcome=="OK") {
                val teacher=world.agents.filter { it.id!=citizen.id && hypot(it.x-citizen.x,it.z-citizen.z)<=2.5 }.minByOrNull { it.id }
                if(teacher!=null) world.minds[teacher.id]?.let { bank=bank.learnFrom(teacher.id,it.memory,world.tick) }
            }
            val recalled=NativeMemory.recall(bank,citizen,world.tick)
            bank=bank.recordRecall(recalled.map { it.episode.eventId },world.tick)

            // A successful low-thermal sleep opens a bounded prioritized ripple burst.
            // Each selected episode updates the real native LTC weights, not only a replay counter.
            if (learn && citizen.activity==Activity.SLEEP && outcome=="OK" && policyScale>=0.65 && sleepReplayBudget>0) {
                val thermalBatch=if(policyScale>=0.90) HippocampalSleepConsolidator.DEFAULT_REPLAY_BATCH_SIZE else 4
                val batchSize=minOf(thermalBatch,sleepReplayBudget)
                val replayEpisodes=HippocampalSleepConsolidator.prioritizedReplay(bank,world.tick,batchSize)
                sleepReplayBudget-=replayEpisodes.size
                replayEpisodes.forEach { episode ->
                    val replayData=checkNotNull(networkBuffer.get()).apply { clear() }
                    learnedParameters.forEach(replayData::putDouble)
                    learnedHidden.forEach(replayData::putDouble)
                    episode.embedding.take(12).forEach { replayData.putDouble(it.toDouble()) }
                    replayData.putDouble(episode.reward)
                        .putDouble(episode.importance)
                        .putDouble(episode.action.ordinal.toDouble())
                        .putDouble(1.0)
                    repeat(8) { replayData.putDouble(0.0) }
                    check(advance(replayData)) { "Falha no ripple replay nativo" }
                    learnedParameters=List(248) { replayData.getDouble(it*8) }
                    learnedHidden=List(8) { replayData.getDouble((248+it)*8) }
                }
            }
            val next=old.copy(memory=bank,parameters=learnedParameters,hidden=learnedHidden,exposure=exposure,learnedTick=if(learn) world.tick else old.learnedTick,novelty=novelty,behaviors=archive)
            check(next.valid(world.tick))
            minds[citizen.id]=next
            val activeInference = ActiveInferencePianoEngine.evaluate(
                needs = ActiveInferenceNeeds(n.hydration, n.energy, n.glucose, n.social, n.curiosity),
                novelty = novelty,
                predictionError = old.psyche.lastPredictionError,
                openness = old.personality[0],
                dopamine = old.psyche.dopamine,
                previousReward = reward.coerceIn(-1.0, 1.0),
                thermalScale = policyScale.coerceIn(0.0, 1.0),
                currentActivity = citizen.activity,
                learnedValues = bank.reflections.associate { it.action to (it.expectedReward / 5.0).coerceIn(-1.0, 1.0) }
            )
            val activeWorkspace = activeInference.globalWorkspaceTopThree.map { it.activity }.toSet()
            val society = SocietyAdicoCliodynamicsEngine.evaluate(world, citizen, socialGraphIndex)
            val scores=decisionScores.toMutableList()
            scores[citizen.activity.ordinal]+=reflexVote*0.015
            val needs=listOf(1-n.hydration,1-n.energy,1-n.glucose,1-n.energy,1-n.social,1-n.curiosity,1-n.curiosity,1-n.curiosity)
            val exploration=Canonical.stream(world.seed,citizen.id,world.tick,"intrinsic-exploration-v1")
            Activity.entries.forEachIndexed { i,activity->
                // Learned value, homeostasis, distance and intrinsic novelty all affect the next decision.
                val room=world.rooms.first { it.id==activity.room }
                val experience=recalled.filter { it.episode.action==activity }
                val expected=if(experience.isEmpty()) bank.reflections.firstOrNull { it.action==activity }?.expectedReward ?: 0.0
                    else experience.sumOf { it.episode.reward*it.score }/experience.sumOf { it.score.toDouble() }.coerceAtLeast(.001)
                val technique=bank.procedures.firstOrNull { it.action==activity }?.expectedReward ?: 0.0
                // Seeded Gumbel exploration scales with curiosity and lack of novelty; outputs are journaled.
                val temperature=(.04+.20*old.personality[0]*(1-novelty))*policyScale
                val u=exploration.nextDouble().coerceIn(1e-12,1-1e-12)
                scores[i]+=-ln(-ln(u))*temperature
                val psyche=old.psyche
                val ignition=(psyche.ignitionIndex-.65).coerceAtLeast(0.0)
                val antiCollapseBonus = SemanticCollapseGuard.noveltyEpistemicBonus(activity.name, world.actionCounts)
                val attentionWeight = if (activity in activeWorkspace) 0.25 else 0.05
                scores[i]+=(activeInference.utilityByActivity[activity] ?: 0.0)*attentionWeight+
                    (society.utilityByActivity[activity] ?: 0.0)*0.35+
                    policy[i]*policyScale+antiCollapseBonus+expected*.4+technique*.15+
                    bank.learningFor(activity).practiceBonus(world.tick)+bank.workingMemoryBonus(activity,world.tick)+
                    needs[i]*1.7+.4/sqrt(exposure[i]+1.0)-
                    hypot(room.x-citizen.x,room.z-citizen.z)*.025
                scores[i]+=when(activity) {
                    Activity.STUDY -> psyche.dopamine*.12+ignition*.45
                    Activity.TALK -> psyche.oxytocin*.12+maxOf(0.0,psyche.pleasure)*.04
                    Activity.SLEEP -> psyche.cortisol*.06
                    Activity.BUILD -> -psyche.cortisol*.05
                    else -> 0.0
                }
                if(activity==Activity.SLEEP && n.energy>.8) scores[i]-=1
                if(activity==Activity.BUILD && ((world.balances["market/timber"] ?: 0)<4)) scores[i]-=3
                if(activity==Activity.WRITE && (world.balances["market/paper"] ?: 0)==0) scores[i]-=3
                if(activity==Activity.EAT && (world.balances["${citizen.id}/coins"] ?: 0)==0) scores[i]-=3
            }
            val decision=Activity.entries[scores.indices.maxBy { scores[it] }]
            decisions[citizen.id]=decision
            val expectedSensors=NativeAutonomy.predictNext(
                world,citizen,decision,autonomy.actualSensory.getValue(citizen.id)
            )
            minds[citizen.id]=minds.getValue(citizen.id).copy(
                psyche=minds.getValue(citizen.id).psyche.copy(expectedSensory=expectedSensors,initialized=true)
            )
        }
        return CognitiveFrame(
            world.tick,Canonical.hash(Canonical.state(world)),minds,decisions,
            socialSynchrony=autonomy.orderParameter
        )
    }
}
