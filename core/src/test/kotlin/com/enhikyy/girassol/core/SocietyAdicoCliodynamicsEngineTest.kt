package com.enhikyy.girassol.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SocietyAdicoCliodynamicsEngineTest {
    @Test fun classifiesDunbarRingsAtCanonicalBoundaries() {
        assertEquals(DunbarRing.INNER_5, SocietyAdicoCliodynamicsEngine.classifyDunbarRing(5))
        assertEquals(DunbarRing.CLOSE_15, SocietyAdicoCliodynamicsEngine.classifyDunbarRing(6))
        assertEquals(DunbarRing.COMMUNITY_50, SocietyAdicoCliodynamicsEngine.classifyDunbarRing(50))
        assertEquals(DunbarRing.PUBLIC_150, SocietyAdicoCliodynamicsEngine.classifyDunbarRing(150))
        assertEquals(DunbarRing.OUTER, SocietyAdicoCliodynamicsEngine.classifyDunbarRing(151))
    }
    @Test fun boundedConfidenceControlsDeffuantAssimilation() {
        assertTrue(SocietyAdicoCliodynamicsEngine.deffuantStep(0.0, 0.2, 0.8) > 0.0)
        assertEquals(0.0, SocietyAdicoCliodynamicsEngine.deffuantStep(0.0, 0.9, 0.8), 1e-12)
    }
    @Test fun friedkinJohnsonRetainsIntrinsicOpinionAnchor() {
        assertEquals(-0.2, SocietyAdicoCliodynamicsEngine.friedkinJohnson(1.0, -0.5, 0.2), 1e-12)
    }
    @Test fun adicoPenalizesUnsustainableBuildAndReturnsFiniteCliodynamics() {
        val world = WorldActor.genesis()
        val citizen = world.agents.first()
        val constrained = world.copy(balances = world.balances + ("market/timber" to 0))
        val result = SocietyAdicoCliodynamicsEngine.evaluate(constrained, citizen)
        assertTrue(result.utilityByActivity.getValue(Activity.BUILD) < 0.0)
        assertTrue(result.cliodynamicsPsi.isFinite())
        assertTrue(result.friedkinJohnsenOpinion in -1.0..1.0)
    }
}
