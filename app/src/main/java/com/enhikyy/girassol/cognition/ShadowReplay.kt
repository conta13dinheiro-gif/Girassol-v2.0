package com.enhikyy.girassol.cognition

import com.enhikyy.girassol.core.*
import java.util.concurrent.CancellationException

/** Evaluates actual WorldActor/native minds with the same version and genome as live execution. */
internal object ShadowReplay {
    data class Rollout(val metrics: ReplayMetrics,val checkpoint: Checkpoint,val actions: List<ActionRecord>,val safety: RsiMetrics) {
        val window get() = RsiWindow(metrics,safety)
    }
    data class Result(val revision: PolicyRevision?,val evaluated: Int,val baseline: ReplayMetrics,val candidate: ReplayMetrics,val message: String)
    @JvmOverloads fun rollout(origin: Checkpoint,weights: List<Double>,actions: List<ActionRecord>,horizon: Int,cancel: ()->Boolean,
        genome: CognitivePolicyGenome=origin.state.evolution?.cognitive?.candidate ?: CognitivePolicyGenome()): Rollout {
        require(horizon in 1..4096 && genome.valid())
        val actor=WorldActor(origin,WorldActor.CURRENT_RULES_VERSION); var last=actions
        var resilience=0.0; var harmony=0.0; var energy=0.0; var observations=0.0
        var freeEnergy=0.0; var brier=0.0; var forecasts=0; var llmCalls=0
        val behavior=LongArray(8)
        val beforeNovel=origin.state.minds.values.sumOf { it.behaviors.size }
        val beforeConsumption=listOf(Activity.WATER,Activity.COFFEE,Activity.EAT).sumOf { origin.state.actionCounts[it.name] ?: 0 }
        repeat(horizon) {
            if(cancel() || Thread.currentThread().isInterrupted) throw CancellationException()
            val input=NativeMind.evaluate(actor.state,last,weights,genome=genome)
            check(input.applicable(actor.state))
            val spatial=com.enhikyy.girassol.physics.NativeSpatial.advance(actor.state,actor.intentions(cognition=input))
            val next=actor.step(cognition=input,spatial=spatial); last=next.event.actions
            check(WorldActor.violations(next.state).isEmpty())
            for(action in last) {
                val trace=input.minds.getValue(action.agentId).decision
                freeEnergy+=trace.freeEnergy[action.activity.ordinal]
                // Movement has no settled success label. Never train calibration on a censored outcome.
                if(action.outcome!="MOVE") {
                    val p=trace.successProbability[action.activity.ordinal]
                    val error=p-if(action.outcome=="OK") 1.0 else 0.0
                    brier+=error*error; forecasts++; behavior[action.activity.ordinal]++
                }
            }
            if(next.event.neural!=null) llmCalls++
            val people=next.state.agents
            observations += people.size
            resilience+=people.count { minOf(it.needs.energy,it.needs.hydration,it.needs.glucose)>=.1 }.toDouble()/people.size
            harmony+=people.map { it.needs.social }.average()
            energy+=people.map { it.needs.energy }.average()
        }
        val w=actor.state
        val discoveries=(w.minds.values.sumOf { it.behaviors.size }-beforeNovel).coerceAtLeast(0).toDouble()
        val consumption=listOf(Activity.WATER,Activity.COFFEE,Activity.EAT).sumOf { w.actionCounts[it.name] ?: 0 }-beforeConsumption
        val metrics=ReplayMetrics((discoveries/observations).coerceIn(0.0,1.0),resilience/horizon,harmony/horizon,
            (energy/horizon/(1+consumption/observations)).coerceIn(0.0,1.0))
        return Rollout(metrics,actor.checkpoint(),last,RsiMetrics(freeEnergy/observations,resilience/horizon,
            if(forecasts==0) 1.0 else brier/forecasts,BoundedRsiEngine.semanticEntropy(behavior),100.0*llmCalls/horizon,0,forecasts))
    }
    @JvmOverloads fun search(origin: Checkpoint,actions: List<ActionRecord>,cancel: ()->Boolean,progress: (Int,Int)->Unit,
        record: (RsiTrial)->Unit = {}): Result {
        val horizon=256; val total=23
        val base=origin.state.evolution?.weights ?: List(8) { 0.0 }
        val genome=origin.state.evolution?.cognitive?.candidate ?: CognitivePolicyGenome()
        var evaluated=0
        fun test(cp: Checkpoint,g: CognitivePolicyGenome,last: List<ActionRecord>) = rollout(cp,base,last,horizon,cancel,g).also { evaluated++; progress(evaluated,total) }
        val baseline=test(origin,genome,actions)
        val optimizer=NativeCmaes.Search(genome.vector(),origin.state.seed xor origin.state.tick)
        val eligible=mutableListOf<RsiTrial>()
        var current: RsiTrial?=null
        var best=baseline.metrics
        try {
            repeat(2) { generation ->
                val scores=optimizer.candidates().mapIndexed { index,vector ->
                    if(cancel() || Thread.currentThread().isInterrupted) throw CancellationException()
                    val candidate=genome.mutate(vector)
                    val ordinal=generation*10+index
                    val id=Canonical.hash("${origin.eventHash}:$ordinal:${candidate.signature()}")
                    val trial=RsiTrial(id,origin.state.seed,origin.state.tick,Canonical.hash(Canonical.state(origin.state)),origin.eventHash,
                        WorldActor.CURRENT_RULES_VERSION,ordinal,genome,candidate)
                    check(trial.valid()); record(trial); current=trial
                    val result=test(origin,candidate,actions)
                    if(result.metrics.score>best.score) best=result.metrics
                    val evidence=trial.copy(trainingBaseline=baseline.window,trainingCandidate=result.window)
                    val rejection=BoundedRsiEngine.rejection(baseline.window,result.window)
                    if(rejection==null) {
                        val trained=evidence.copy(stage="TRAINED",reason="TRAINED")
                        record(trained); eligible+=trained
                    } else record(evidence.copy(stage="REJECTED",reason=rejection))
                    current=null
                    result.safety.objective
                }
                optimizer.adapt(scores)
            }
            val winner=eligible.maxWithOrNull(compareBy<RsiTrial> { it.trainingCandidate!!.safety.objective }.thenBy { -it.ordinal })
            for(trial in eligible.toList()) if(trial!=winner) {
                record(trial.copy(stage="REJECTED",reason="NOT_SELECTED")); eligible.remove(trial)
            }
            eligible.clear()
            if(winner==null) return Result(null,evaluated,baseline.metrics,best,"Nenhum genoma passou por todos os gates de treino.")
            current=winner
            // A second disjoint window starts from the baseline future with equal physical state.
            val validationBase=test(baseline.checkpoint,genome,baseline.actions)
            current=winner.copy(validationBaseline=validationBase.window)
            val validationCandidate=test(baseline.checkpoint,winner.candidate,baseline.actions)
            val final=winner.copy(validationBaseline=validationBase.window,validationCandidate=validationCandidate.window)
            val rejection=BoundedRsiEngine.rejection(validationBase.window,validationCandidate.window)
            if(rejection!=null) {
                record(final.copy(stage="REJECTED",reason=rejection)); current=null
                return Result(null,evaluated,baseline.metrics,best,"Genoma recusado na janela independente: $rejection")
            }
            val change=VerifiedGenomeChange(genome,winner.candidate,baseline.window,winner.trainingCandidate!!,validationBase.window,validationCandidate.window)
            val revision=PolicyRevision(origin.state.seed,origin.state.tick,Canonical.hash(Canonical.state(origin.state)),origin.eventHash,
                base,base,baseline.metrics,winner.trainingCandidate!!.legacy,validationBase.metrics,validationCandidate.metrics,horizon,evaluated,
                "VPO/CMA-ES-genome-v1",WorldActor.CURRENT_RULES_VERSION,change)
            check(revision.valid(origin.state.seed,origin.state.tick))
            record(final.copy(stage="VERIFIED",reason="VERIFIED")); current=null
            return Result(revision,evaluated,baseline.metrics,best,"Genoma verificado nas duas janelas; aguarda transação de promoção.")
        } catch(error: Exception) {
            val interrupted=error is CancellationException || error is InterruptedException || Thread.currentThread().isInterrupted
            val restoreInterrupt=Thread.interrupted()
            try {
                (eligible+listOfNotNull(current)).distinctBy { it.id }.forEach {
                    record(it.copy(stage=if(interrupted) "CANCELLED" else "ERROR",reason=if(interrupted) "CANCELLED" else "EVALUATION_ERROR"))
                }
            } finally { if(restoreInterrupt) Thread.currentThread().interrupt() }
            if(!interrupted) throw error
            return Result(null,evaluated,baseline.metrics,best,"Experimento cancelado; propostas preservadas no ledger.")
        }
    }
}
