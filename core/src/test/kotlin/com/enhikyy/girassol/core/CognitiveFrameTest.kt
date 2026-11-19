package com.enhikyy.girassol.core
import org.junit.Assert.*
import org.junit.Test

class CognitiveFrameTest {
    private fun frame(w: WorldState): CognitiveFrame = CognitiveFrame(w.tick,Canonical.hash(Canonical.state(w)),
        w.agents.associate { it.id to MindState.initial(w,it) },w.agents.associate { it.id to Activity.STUDY })
    @Test fun legacySerializationRemainsByteIdenticalWhenNoMindExists() {
        val w=WorldActor.genesis()
        assertFalse(Canonical.state(w).contains("minds"))
        val e=WorldActor().step().event
        assertFalse(Canonical.event(e).contains("cognition"))
        assertEquals(w,Canonical.state(Canonical.state(w)))
    }
    @Test fun nativeInputAndWeightsReplayWithoutRunningNativeCode() {
        val writer=WorldActor(rulesVersion=4); val reader=WorldActor()
        repeat(20) {
            val input=frame(writer.state)
            assertTrue(input.applicable(writer.state))
            val transition=writer.step(cognition=input)
            assertEquals(4,transition.event.version); assertTrue(transition.event.valid())
            reader.replay(Canonical.event(Canonical.event(transition.event)))
            assertEquals(writer.checkpoint(),reader.checkpoint())
        }
    }
    @Test fun staleMalformedAndForeignFramesAreRejectedAtTheBoundary() {
        val actor=WorldActor(rulesVersion=4); val f=frame(actor.state)
        assertFalse(f.copy(sourceHash="f".repeat(64)).applicable(actor.state))
        assertFalse(f.copy(sourceTick=1).applicable(actor.state))
        assertFalse(f.copy(minds=emptyMap()).applicable(actor.state))
        val broken=f.minds.toMutableMap(); broken["agent0"]=broken.getValue("agent0").copy(hidden=List(8) { 2.0 })
        assertFalse(f.copy(minds=broken).applicable(actor.state))
        val t=actor.step(cognition=f.copy(sourceTick=100))
        assertEquals(2,t.event.version); assertTrue(t.state.minds.isEmpty())
    }
    @Test fun publicationOwnsItsLearnedBuffersAndDoesNotAliasTheProducer() {
        val actor=WorldActor(rulesVersion=4)
        val initial=frame(actor.state)
        val weights=initial.minds.getValue("agent0").parameters.toMutableList()
        val minds=initial.minds.toMutableMap().apply { this["agent0"]=getValue("agent0").copy(parameters=weights) }
        val decisions=initial.decisions.toMutableMap()
        val transition=actor.step(cognition=initial.copy(minds=minds,decisions=decisions))
        val stateBytes=Canonical.state(actor.state); val eventBytes=Canonical.event(transition.event)
        weights[0]=7.0; minds.clear(); decisions.clear()
        assertEquals(stateBytes,Canonical.state(actor.state)); assertEquals(eventBytes,Canonical.event(transition.event))
        assertTrue(transition.event.valid())
    }
    @Test fun currentUrgentNeedsOverrideLearnedPreference() {
        val w=WorldActor.genesis().copy(agents=WorldActor.genesis().agents.map { it.copy(needs=it.needs.copy(hydration=.1)) })
        val actor=WorldActor(Checkpoint(w,0,WorldActor.GENESIS_HASH),4)
        val transition=actor.step(cognition=frame(w))
        assertTrue(transition.event.actions.all { it.activity==Activity.WATER })
        assertTrue(WorldActor.violations(transition.state).isEmpty())
    }
    @Test fun descendantInheritsDeterministicLtcPersonalityAndValidatedParentalSkill() {
        val world=WorldActor.genesis().copy(tick=12)
        val one=world.agents[0]
        val two=world.agents[1]
        val child=one.copy(
            id="child_test",
            name="Descendente",
            lineage="${one.lineage}·${two.lineage}",
            genome=Genome(one.genome.curiosity,two.genome.sociability,2)
        )
        val first=MindState.initial(world,one)
        val second=MindState.initial(world,two).copy(
            memory=MemoryBank(
                procedures=listOf(Procedure(Activity.STUDY,3,.8,10,10)),
                reflectedTick=10
            )
        )
        val inherited=MindState.inherit(world,child,first,second)
        assertTrue(inherited.valid(world.tick))
        assertEquals(child.genome.curiosity.express(),inherited.personality[0],0.0)
        assertEquals(child.genome.sociability.express(),inherited.personality[2],0.0)
        assertTrue(inherited.memory.procedures.any { it.action==Activity.STUDY && it.teacher==two.id && it.successes>=3 })
        assertEquals(inherited,MindState.inherit(world,child,first,second))
    }

}
