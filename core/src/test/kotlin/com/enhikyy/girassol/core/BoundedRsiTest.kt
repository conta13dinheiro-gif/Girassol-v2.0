package com.enhikyy.girassol.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class BoundedRsiTest {
    private val base=RsiWindow(ReplayMetrics(.1,.6,.5,.5),RsiMetrics(1.0,.6,.20,.6,0.0,0,100))
    private val better=RsiWindow(ReplayMetrics(.4,.8,.7,.7),RsiMetrics(.5,.8,.20,.6,0.0,0,100))
    @Test fun gatesRejectEachFailureAndRetainOriginalParetoContract() {
        assertNull(BoundedRsiEngine.rejection(base,better))
        assertEquals("INVARIANT",BoundedRsiEngine.rejection(base,better.copy(safety=better.safety.copy(invariantViolations=1))))
        assertEquals("H_SEM",BoundedRsiEngine.rejection(base,better.copy(safety=better.safety.copy(semanticEntropy=.349))))
        assertEquals("BRIER",BoundedRsiEngine.rejection(base,better.copy(safety=better.safety.copy(brierScore=.211))))
        assertEquals("DELTA_J",BoundedRsiEngine.rejection(base,better.copy(safety=base.safety)))
        assertEquals("LEGACY_PARETO",BoundedRsiEngine.rejection(base,better.copy(legacy=better.legacy.copy(harmony=.49))))
        assertEquals("INVALID_EVIDENCE",BoundedRsiEngine.rejection(base,better.copy(safety=better.safety.copy(observations=0))))
        assertNull(BoundedRsiEngine.rejection(base,better.copy(safety=better.safety.copy(brierScore=.210,semanticEntropy=.35))))
    }
    @Test fun genomeIsStrictBoundedAndUsesGrammarWithNoCodeExecution() {
        assertEquals(27,FiniteGbnf.compile(VpoRule.GBNF).size)
        assertEquals(setOf("ab","ac"),FiniteGbnf.compile("root ::= \"a\" tail\ntail ::= \"b\" | \"c\""))
        for(invalid in listOf("root ::= root", "root ::= missing", "root ::= [0-9]", "root ::= a a a a a a a a\na ::= \"0\" | \"1\"")) {
            try { FiniteGbnf.compile(invalid); fail("Unbounded/unsupported grammar accepted") }
            catch(_: IllegalArgumentException) { }
        }
        val g=CognitivePolicyGenome()
        assertTrue(g.valid()); assertFalse(g.copy(memRlLearningRate=Double.NaN).valid())
        assertFalse(g.copy(intentionSwitchHysteresis=.351).valid())
        assertFalse(g.copy(activeVpoRules=listOf("IF energy < 0.20 THEN SLEEP; population=999")).valid())
        assertFalse(g.copy(activeVpoRules=List(9){VpoRule.PRODUCTIONS[1]}).valid())
        for(v in listOf(-2.0,0.0,2.0)) {
            val mutant=g.mutate(List(8){v})
            assertTrue(mutant.valid()); assertTrue(mutant.differsFrom(g)); assertEquals(g.version+1,mutant.version)
            assertEquals(mutant,Json.decodeFromString<CognitivePolicyGenome>(Json.encodeToString(mutant)))
            assertEquals(mutant.signature(),Json.decodeFromString<CognitivePolicyGenome>(Json.encodeToString(mutant)).signature())
        }
        assertEquals(0.0,BoundedRsiEngine.semanticEntropy(longArrayOf(100,0,0,0,0,0,0,0)),0.0)
        assertEquals(1.0,BoundedRsiEngine.semanticEntropy(LongArray(8){100}),1e-12)
        assertTrue(VpoRule.parse("IF energy < 0.20 THEN SLEEP")!!.bonus(WorldActor.genesis().agents.first().copy(needs=Needs(energy=.1)))>0)
    }
    @Test fun newRevisionsNeedBothWindowsAndUnchangedPhysicalBiases() {
        val w=WorldActor.genesis(); val genome=CognitivePolicyGenome(); val candidate=genome.mutate(List(8){0.0})
        val proof=VerifiedGenomeChange(genome,candidate,base,better,base,better)
        val revision=PolicyRevision(w.seed,0,Canonical.hash(Canonical.state(w)),WorldActor.GENESIS_HASH,List(8){0.0},List(8){0.0},
            base.legacy,better.legacy,base.legacy,better.legacy,256,23,"VPO/CMA-ES-genome-v1",WorldActor.CURRENT_RULES_VERSION,proof)
        assertTrue(revision.applicable(w))
        assertFalse(revision.copy(weights=List(8){.1}).applicable(w))
        assertFalse(revision.copy(cognitive=proof.copy(validationCandidate=base)).applicable(w))
        assertFalse(revision.copy(rulesVersion=7).valid(w.seed,0))
        assertFalse(revision.copy(cognitive=proof.copy(baseline=genome.copy(version=2))).applicable(w))
        val trial=RsiTrial(Canonical.hash("${WorldActor.GENESIS_HASH}:0:${candidate.signature()}"),w.seed,0,Canonical.hash(Canonical.state(w)),WorldActor.GENESIS_HASH,
            WorldActor.CURRENT_RULES_VERSION,0,genome,candidate)
        assertTrue(trial.valid()); assertFalse(trial.copy(stage="VERIFIED",reason="VERIFIED").valid())
        assertTrue(trial.copy(stage="VERIFIED",reason="VERIFIED",trainingBaseline=base,trainingCandidate=better,validationBaseline=base,validationCandidate=better).valid())
    }
}
