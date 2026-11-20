package com.enhikyy.girassol.core
import org.junit.Test
import org.junit.Assert.*
import kotlin.math.ln
class ActiveInferenceTest {
    @Test fun exactPosteriorAttainsNegativeLogEvidence() {
        for(p in listOf(.1,.5,.9)) for(l in listOf(.1,.5,.9)) {
            val e=ActiveInferencePianoEngine.evidence(p,l)
            assertEquals(-ln(e.success),e.freeEnergy,1e-12)
            assertTrue(e.expectedFreeEnergy>=0)
            assertEquals(e.pragmatic-e.epistemic,e.expectedFreeEnergy,1e-12)
            assertTrue(e.epistemic>= -1e-12)
        }
    }
    @Test fun workspacePublishesInStableOrder() {
        repeat(30) {
            val result=PianoWorkspace.aggregate(*Array(5) { module -> { List(8) { action -> (module*8+action).toDouble() } } })
            assertEquals(List(8) { action -> (0..4).sumOf { it*8.0+action } },result)
        }
    }
    @Test fun workspaceProtectsHomeostasisAndHasStableTies() {
        val c=WorldActor.genesis().agents.first().copy(needs=Needs(hydration=.01))
        val result=ActiveInferencePianoEngine.select(c,List(8){0.0},List(8){.5})
        assertEquals(Activity.WATER,result.action)
        assertEquals(3,result.topThree.distinct().size)
    }
}
