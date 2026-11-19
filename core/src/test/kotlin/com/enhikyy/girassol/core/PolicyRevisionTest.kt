package com.enhikyy.girassol.core
import org.junit.Assert.*
import org.junit.Test
class PolicyRevisionTest {
    private fun revision(w: WorldState) = PolicyRevision(w.seed,w.tick,Canonical.hash(Canonical.state(w)),WorldActor.GENESIS_HASH,
        List(8) { 0.0 },List(8) { .1 },ReplayMetrics(.1,.7,.6,.5),ReplayMetrics(.4,.8,.7,.6),
        ReplayMetrics(.1,.7,.6,.5),ReplayMetrics(.4,.8,.7,.6),256,23)
    @Test fun rulesEvidenceIsVersionedWithoutChangingLegacyCanonicalBytes() {
        val world=WorldActor.genesis()
        val old=revision(world)
        val frame=CognitiveFrame(world.tick,Canonical.hash(Canonical.state(world)),
            world.agents.associate { it.id to MindState.initial(world,it) },
            world.agents.associate { it.id to Activity.STUDY },old)
        val event=WorldActor(Checkpoint(world,0,WorldActor.GENESIS_HASH),4).step(cognition=frame).event
        assertFalse(Canonical.event(event).contains("rulesVersion"))
        val current=old.copy(rulesVersion=WorldActor.CURRENT_RULES_VERSION)
        assertTrue(current.valid(world.seed,world.tick))
        assertFalse(current.copy(rulesVersion=999).valid(world.seed,world.tick))
    }
    @Test fun onlyVerifiedParetoGainsAcrossBothWindowsCanBePromoted() {
        val w=WorldActor.genesis(); val r=revision(w); assertTrue(r.applicable(w))
        assertFalse(r.copy(validationCandidate=r.validationBaseline).applicable(w))
        assertFalse(r.copy(trainingCandidate=r.trainingCandidate.copy(resilience=.69)).applicable(w))
        assertFalse(r.copy(weights=List(8) { 3.0 }).applicable(w))
        assertFalse(r.copy(baseWeights=List(8) { .01 }).applicable(w))
    }
    @Test fun promotedWeightsAndEvidenceAreCanonicalAndReplayable() {
        val w=WorldActor.genesis(); val actor=WorldActor(Checkpoint(w,0,WorldActor.GENESIS_HASH),4)
        val frame=CognitiveFrame(w.tick,Canonical.hash(Canonical.state(w)),w.agents.associate { it.id to MindState.initial(w,it) },
            w.agents.associate { it.id to Activity.STUDY },revision(w))
        val t=actor.step(cognition=frame); assertEquals(frame.evolution,t.state.evolution)
        val reader=WorldActor(); reader.replay(Canonical.event(Canonical.event(t.event))); assertEquals(actor.checkpoint(),reader.checkpoint())
    }
}
