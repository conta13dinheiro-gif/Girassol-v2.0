package com.enhikyy.girassol.core
import org.junit.Assert.*
import org.junit.Test
class MilestonesTest {
    @Test fun originAndConstructionHaveActualCausalEvents() {
        val actor=WorldActor(); val t=actor.step()
        assertEquals("origin",Milestones.detect(null,t).single().kind)
        val before=actor.state
        val room=Room("annex", "Jardim", 1.0,1.0,1.0,1.0,1)
        val built=t.copy(state=t.state.copy(rooms=t.state.rooms+room),event=t.event.copy(actions=listOf(ActionRecord("agent0",Activity.BUILD,"OK"))))
        val cause=Milestones.detect(before,built).single()
        assertEquals("architecture",cause.kind); assertEquals(listOf("agent0"),cause.agents)
        assertTrue(cause.cause.contains("Jardim"))
        assertTrue(Milestones.detect(before,t).isEmpty())
    }
}
