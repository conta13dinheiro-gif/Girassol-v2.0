package com.enhikyy.girassol.core

import org.junit.Assert.*
import org.junit.Test

class CivicEvolutionTest {
    @Test fun advancesBoundedSocialEducationEconomyAndArchiveState() {
        val before=WorldActor.genesis()
        val actor=WorldActor(before.copy(civic=CivicEvolutionState()))
        val transition=actor.step()
        val civic=transition.state.civic
        assertTrue(civic.valid(transition.state.tick))
        assertEquals(transition.state.agents.size,civic.opinions.size)
        assertTrue(civic.triadBalance in 0.0..1.0)
        assertTrue(civic.ledgerBalanced)
        assertEquals(144,civic.pheromones.size)
        assertTrue(civic.archive.size<=64)
    }

    @Test fun civicStateSurvivesCanonicalRoundTrip() {
        val actor=WorldActor()
        val state=actor.step().state
        val restored=Canonical.state(Canonical.state(state))
        assertEquals(state.civic,restored.civic)
        assertTrue(WorldActor.violations(restored).isEmpty())
    }

    @Test fun doubleEntryRejectsUnbalancedCommodityMutation() {
        val before=WorldActor.genesis()
        val broken=before.copy(balances=before.balances+("market/water" to ((before.balances["market/water"]?:0)+1)))
        val civic=CivicEvolutionEngine.advance(CivicEvolutionState(),before,broken,emptyList())
        assertFalse(civic.ledgerBalanced)
        assertFalse(civic.valid(broken.tick))
    }
}
