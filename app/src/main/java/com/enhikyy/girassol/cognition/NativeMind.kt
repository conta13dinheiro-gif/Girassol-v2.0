package com.enhikyy.girassol.cognition

import com.enhikyy.girassol.core.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/** Called only by the service's world thread; all learned parameters enter its canonical event. */
internal object NativeMind {
    init { System.loadLibrary("girassol_projection") }
    private external fun advance(data: ByteBuffer): Boolean
    private val networkBuffer=ThreadLocal.withInitial { ByteBuffer.allocateDirect(280*8).order(ByteOrder.nativeOrder()) }
    fun evaluate(world: WorldState,lastActions: List<ActionRecord> = emptyList(),policy: List<Double> = world.evolution?.weights ?: List(8) { 0.0 }): CognitiveFrame {
        require(policy.size==8 && policy.all { it.isFinite() && it in -2.0..2.0 })
        val minds=linkedMapOf<String,MindState>(); val decisions=linkedMapOf<String,Activity>()
        world.agents.forEach { citizen->
            val old=world.minds[citizen.id] ?: MindState.initial(world,citizen)
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
            var bank=old.memory.observe(world,citizen,lastActions.firstOrNull { it.agentId==citizen.id })
            if(citizen.acting && citizen.activity==Activity.TALK && outcome=="OK") {
                val teacher=world.agents.filter { it.id!=citizen.id && hypot(it.x-citizen.x,it.z-citizen.z)<=2.5 }.minByOrNull { it.id }
                if(teacher!=null) world.minds[teacher.id]?.let { bank=bank.learnFrom(teacher.id,it.memory,world.tick) }
            }
            val recalled=NativeMemory.recall(bank,citizen,world.tick)
            val next=old.copy(memory=bank,parameters=values(0,248),hidden=values(248,8),exposure=exposure,learnedTick=if(learn) world.tick else old.learnedTick,novelty=novelty,behaviors=archive)
            check(next.valid(world.tick))
            minds[citizen.id]=next
            val scores=values(272,8).toMutableList()
            val needs=listOf(1-n.hydration,1-n.energy,1-n.glucose,1-n.energy,1-n.social,1-n.curiosity,1-n.curiosity,1-n.curiosity)
            Activity.entries.forEachIndexed { i,activity->
                // Learned value, homeostasis, distance and intrinsic novelty all affect the next decision.
                val room=world.rooms.first { it.id==activity.room }
                val experience=recalled.filter { it.episode.action==activity }
                val expected=if(experience.isEmpty()) bank.reflections.firstOrNull { it.action==activity }?.expectedReward ?: 0.0
                    else experience.sumOf { it.episode.reward*it.score }/experience.sumOf { it.score.toDouble() }.coerceAtLeast(.001)
                val technique=bank.procedures.firstOrNull { it.action==activity }?.expectedReward ?: 0.0
                scores[i]+=policy[i]+expected*.4+technique*.15+needs[i]*1.7+.4/sqrt(exposure[i]+1.0)-hypot(room.x-citizen.x,room.z-citizen.z)*.025
                if(activity==Activity.SLEEP && n.energy>.8) scores[i]-=1
                if(activity==Activity.BUILD && (world.rooms.size>=10 || (world.balances["market/timber"] ?: 0)<4)) scores[i]-=3
                if(activity==Activity.WRITE && (world.balances["market/paper"] ?: 0)==0) scores[i]-=3
                if(activity==Activity.EAT && (world.balances["${citizen.id}/coins"] ?: 0)==0) scores[i]-=3
            }
            decisions[citizen.id]=Activity.entries[scores.indices.maxBy { scores[it] }]
        }
        return CognitiveFrame(world.tick,Canonical.hash(Canonical.state(world)),minds,decisions)
    }
}
