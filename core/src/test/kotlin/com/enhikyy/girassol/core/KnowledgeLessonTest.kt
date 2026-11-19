package com.enhikyy.girassol.core

import org.junit.Assert.*
import org.junit.Test

class KnowledgeLessonTest {
    private fun studying(): WorldState {
        val world=WorldActor.genesis();val library=world.rooms.first { it.id=="library" }
        return world.copy(agents=world.agents.map { it.copy(x=library.x,z=library.z,activity=Activity.STUDY,acting=true) })
    }
    private fun lesson(w: WorldState): KnowledgeLesson {
        val metrics=ReplayMetrics(.2,.8,.6,.7)
        return KnowledgeLesson("a".repeat(64),"b".repeat(64),"c".repeat(64),"LOGIC",w.agents[0].id,w.agents[1].id,95,w.tick,metrics,metrics,0,Canonical.hash(Canonical.state(w)),WorldActor.GENESIS_HASH)
    }
    @Test fun evidenceRequiresPhysicalStudyDistinctVerifierAndNoReplayRegression() {
        val w=studying();val input=lesson(w)
        assertTrue(input.applicable(w))
        assertFalse(input.copy(verifier=input.researcher).applicable(w))
        assertFalse(input.copy(candidate=input.candidate.copy(resilience=.1)).valid(w.tick))
        assertFalse(input.copy(originTick=1).valid(w.tick))
        assertFalse(input.applicable(w.copy(agents=w.agents.map { it.copy(acting=false) })))
        assertFalse(input.applicable(w.copy(knowledge=listOf(input))))
    }
    @Test fun externalLearningIsCanonicalAndReplaysWithNoNetworkOrNativeInference() {
        val w=studying();val cp=Checkpoint(w,0,WorldActor.GENESIS_HASH)
        val input=CognitiveFrame(w.tick,Canonical.hash(Canonical.state(w)),w.agents.associate { it.id to MindState.initial(w,it) },w.agents.associate { it.id to Activity.STUDY },lesson=lesson(w))
        val actor=WorldActor(cp,4);val replay=WorldActor(cp)
        val next=actor.step(cognition=input)
        assertEquals(listOf(input.lesson),next.state.knowledge)
        replay.replay(Canonical.event(Canonical.event(next.event)))
        assertEquals(actor.checkpoint(),replay.checkpoint())
        assertFalse(Canonical.state(WorldActor.genesis()).contains("knowledge"))
    }
}
