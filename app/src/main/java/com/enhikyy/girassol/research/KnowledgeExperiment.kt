package com.enhikyy.girassol.research

import com.enhikyy.girassol.core.*
import com.enhikyy.girassol.cognition.*

internal data class StudyEvidence(val seed: Long,val originTick: Long,val originEventHash: String,val sourceHash: String,val candidate: KnowledgeCandidate,val baseline: ReplayMetrics,val studied: ReplayMetrics) {
    fun lesson(world: WorldState): KnowledgeLesson? {
        if(world.seed!=seed || originTick>world.tick) return null
        return KnowledgeLesson(candidate.id,candidate.acquisitionHash,candidate.row.digest,candidate.intent.domain.name,
            candidate.intent.discoverer,checkNotNull(candidate.verifier).agent,minOf(checkNotNull(candidate.researcher).confidence,checkNotNull(candidate.verifier).confidence),world.tick,baseline,studied,originTick,sourceHash,originEventHash).takeIf { it.applicable(world) }
    }
}
/** Original acquisition and dataset identities are preserved across experiments and live use. */
internal fun KnowledgeCandidate.cognitiveEvidence(tick: Long,eventHash: String) = CognitiveEvidence(
    row.digest,acquisitionHash,Canonical.hash(repo),intent.domain.name,checkNotNull(verifier).agent,
    minOf(checkNotNull(researcher).confidence,checkNotNull(verifier).confidence),tick,eventHash)

internal object KnowledgeExperiment {
    fun evaluate(origin: Checkpoint,actions: List<ActionRecord>,candidate: KnowledgeCandidate,cancel: ()->Boolean): StudyEvidence? {
        val first=checkNotNull(candidate.researcher);val second=checkNotNull(candidate.verifier)
        check(EpistemicQuarantine.approved(candidate.row,first,second))
        val world=origin.state
        val citizen=world.agents.first { it.id==candidate.intent.discoverer }
        val old=world.minds[citizen.id] ?: MindState.initial(world,citizen)
        val learned=NativeMind.assimilate(world,citizen.id,old,candidate.cognitiveEvidence(world.tick,origin.eventHash),candidate.row.text) ?: return null
        val modified=world.copy(minds=world.minds+(citizen.id to learned))
        val weights=world.evolution?.weights ?: List(8) { 0.0 }
        val baseline=ShadowReplay.rollout(origin,weights,actions,256,cancel).metrics
        // Same state, seed and actions; only the actual learned network differs.
        val studied=ShadowReplay.rollout(origin.copy(state=modified),weights,actions,256,cancel).metrics
        val evidence=StudyEvidence(world.seed,world.tick,origin.eventHash,Canonical.hash(Canonical.state(world)),candidate,baseline,studied)
        return evidence.takeIf { KnowledgeLesson(candidate.id,candidate.acquisitionHash,candidate.row.digest,candidate.intent.domain.name,
            citizen.id,second.agent,minOf(first.confidence,second.confidence),world.tick,baseline,studied,world.tick,Canonical.hash(Canonical.state(world)),origin.eventHash).valid(world.tick) }
    }
}
