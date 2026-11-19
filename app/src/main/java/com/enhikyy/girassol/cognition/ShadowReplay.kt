package com.enhikyy.girassol.cognition

import com.enhikyy.girassol.core.*
import java.util.concurrent.CancellationException

/** Evaluates the actual WorldActor and actual native minds. No surrogate fitness simulation. */
internal object ShadowReplay {
    data class Rollout(val metrics: ReplayMetrics,val checkpoint: Checkpoint,val actions: List<ActionRecord>)
    data class Result(val revision: PolicyRevision?,val evaluated: Int,val baseline: ReplayMetrics,val candidate: ReplayMetrics,val message: String)
    fun rollout(origin: Checkpoint,weights: List<Double>,actions: List<ActionRecord>,horizon: Int,cancel: ()->Boolean): Rollout {
        val actor=WorldActor(origin,5); var last=actions
        var resilience=0.0; var harmony=0.0; var energy=0.0
        val beforeNovel=origin.state.minds.values.sumOf { it.behaviors.size }
        val beforeConsumption=listOf(Activity.WATER,Activity.COFFEE,Activity.EAT).sumOf { origin.state.actionCounts[it.name] ?: 0 }
        repeat(horizon) {
            if(cancel() || Thread.currentThread().isInterrupted) throw CancellationException()
            val input=NativeMind.evaluate(actor.state,last,weights)
            val spatial=com.enhikyy.girassol.physics.NativeSpatial.advance(actor.state,actor.intentions(cognition=input))
            val next=actor.step(cognition=input,spatial=spatial); last=next.event.actions
            val people=next.state.agents
            resilience+=people.count { minOf(it.needs.energy,it.needs.hydration,it.needs.glucose)>=.1 }.toDouble()/people.size
            harmony+=people.map { it.needs.social }.average()
            energy+=people.map { it.needs.energy }.average()
        }
        val w=actor.state
        val discoveries=(w.minds.values.sumOf { it.behaviors.size }-beforeNovel).coerceAtLeast(0).toDouble()
        val consumption=listOf(Activity.WATER,Activity.COFFEE,Activity.EAT).sumOf { w.actionCounts[it.name] ?: 0 }-beforeConsumption
        val observations=w.agents.size.toDouble()*horizon
        return Rollout(ReplayMetrics((discoveries/observations).coerceIn(0.0,1.0),resilience/horizon,harmony/horizon,
            (energy/horizon/(1+consumption/observations)).coerceIn(0.0,1.0)),actor.checkpoint(),last)
    }
    fun search(origin: Checkpoint,actions: List<ActionRecord>,cancel: ()->Boolean,progress: (Int,Int)->Unit): Result {
        val horizon=256; val generations=2; val total=generations*10+3
        val base=origin.state.evolution?.weights ?: List(8) { 0.0 }
        var evaluated=0
        fun test(cp: Checkpoint,weights: List<Double>,last: List<ActionRecord>): Rollout = rollout(cp,weights,last,horizon,cancel).also { evaluated++; progress(evaluated,total) }
        val baseline=test(origin,base,actions)
        val optimizer=NativeCmaes.Search(base,origin.state.seed xor origin.state.tick)
        var bestWeights=base; var best=baseline.metrics
        repeat(generations) {
            val candidates=optimizer.candidates()
            val scores=candidates.map { weights->
                val result=test(origin,weights,actions)
                if(result.metrics.score>best.score) { best=result.metrics; bestWeights=weights }
                result.metrics.score
            }
            optimizer.adapt(scores)
        }
        if(bestWeights==base || !best.dominates(baseline.metrics)) return Result(null,evaluated,baseline.metrics,best,"Nenhuma política superou os critérios de promoção.")
        // A second, disjoint window starts from the baseline's predicted future, not the training origin.
        val validationBase=test(baseline.checkpoint,base,baseline.actions)
        val validationCandidate=test(baseline.checkpoint,bestWeights,baseline.actions)
        val revision=PolicyRevision(origin.state.seed,origin.state.tick,Canonical.hash(Canonical.state(origin.state)),origin.eventHash,
            base,bestWeights,baseline.metrics,best,validationBase.metrics,validationCandidate.metrics,horizon,evaluated)
        return Result(revision.takeIf { it.valid(origin.state.seed,origin.state.tick) },evaluated,baseline.metrics,best,
            if(revision.valid(origin.state.seed,origin.state.tick)) "Política validada nas duas janelas." else "Ganho de treino recusado na janela de validação.")
    }
}
