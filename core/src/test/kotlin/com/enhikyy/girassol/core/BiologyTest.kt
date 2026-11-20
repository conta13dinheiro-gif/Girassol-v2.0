package com.enhikyy.girassol.core

import org.junit.Assert.*
import org.junit.Test

class BiologyTest {
    private fun basis(i: Int)=List(8) { if(it==i) 1.0 else 0.0 }
    @Test fun generalizedJensenShannonHasIndependentKnownValuesAndReplicationInvariance() {
        val uniform=List(8) { .125 }
        assertEquals(0.0,PopulationSemantics.divergence(List(6) { uniform }),1e-12)
        val two=listOf(basis(0),basis(1))
        assertEquals(1.0/3,PopulationSemantics.divergence(two),1e-12)
        assertEquals(PopulationSemantics.divergence(two),PopulationSemantics.divergence(two+two),1e-12)
        assertEquals(1.0,PopulationSemantics.divergence(List(8,::basis)),1e-12)
        for(bad in listOf(List(8) { 0.0 },List(8) { Double.NaN },listOf(1.1,-.1)+List(6) { 0.0 })) {
            try { PopulationSemantics.divergence(listOf(bad));fail("Invalid profile accepted") } catch(_: IllegalArgumentException) { }
        }
    }
    @Test fun structuredConceptDiversitySeparatesWithinAndBetweenAgents() {
        val base=WorldActor.genesis()
        val disjoint=base.copy(agents=base.agents.mapIndexed { i,a -> a.copy(memories=listOf(Memory(1,1,if(i<3) Activity.WATER else Activity.SLEEP,.1))) })
        val measured=PopulationSemantics.measure(disjoint)
        assertEquals(6,measured.observed);assertEquals(6,measured.samples)
        assertEquals(1.0/3,measured.entropy,1e-12);assertEquals(1.0/3,measured.betweenAgents,1e-12)
        val homogeneous=base.copy(agents=base.agents.map { a -> a.copy(memories=Activity.entries.map { Memory(1,1,it,.1) }) })
        assertEquals(1.0,PopulationSemantics.measure(homogeneous).entropy,1e-12)
        assertEquals(0.0,PopulationSemantics.measure(homogeneous).betweenAgents,1e-12)
        assertEquals(0,PopulationSemantics.measure(base).observed)
        assertEquals(0.0,PopulationSemantics.noveltyBonus(Activity.WATER,PopulationSemantics.measure(base)),0.0)
    }
    @Test fun rareConceptBonusAndLongCountersStayFinite() {
        val base=WorldActor.genesis()
        val collapsed=PopulationSemantics.measure(base.copy(agents=base.agents.map { it.copy(memories=listOf(Memory(1,1,Activity.WATER,.1))) }))
        assertEquals(0.0,PopulationSemantics.noveltyBonus(Activity.WATER,collapsed),0.0)
        assertEquals(.1,PopulationSemantics.noveltyBonus(Activity.STUDY,collapsed),1e-12)
        val counts=Activity.entries.associate { it.name to Int.MAX_VALUE }
        assertEquals(1.0,SemanticCollapseGuard.normalizedPopulationEntropy(counts),1e-12)
        assertEquals(0.0,SemanticCollapseGuard.noveltyEpistemicBonus(Activity.STUDY.name,counts),0.0)
    }
    @Test fun baldwinSelectionRewardsVerifiedBreadthWithoutMutatingAllelesOrConsumingMeiosisRng() {
        val base=WorldActor.genesis();val first=base.agents[0];val second=base.agents[1]
        val initial=MindState.initial(base,first)
        val traces=Activity.entries.map { action -> var trace=SkillLearningTrace(action)
            repeat(16) { trace=trace.observeFsrs(ReviewGrade.GOOD,(it+1).toLong(),.5,true) };trace }
        val qualified=initial.copy(memory=MemoryBank(learning=traces))
        val fitness=PedagogyAndDiploidEvolutionEngine.learningFitness(qualified)
        assertEquals(0.0,PedagogyAndDiploidEvolutionEngine.learningFitness(initial),0.0)
        assertTrue(fitness>.65 && fitness<.67)
        assertEquals(0.0,PedagogyAndDiploidEvolutionEngine.learningFitness(qualified.copy(memory=MemoryBank(learning=traces.map { it.copy(verifiedExecutions=0) }))),0.0)
        var noviceCount=0;var trainedCount=0
        repeat(4096) { seed ->
            val world=base.copy(seed=seed.toLong())
            if(PedagogyAndDiploidEvolutionEngine(world,emptyMap()).admit(first,second,1)) noviceCount++
            if(PedagogyAndDiploidEvolutionEngine(world,mapOf(first.id to qualified,second.id to qualified)).admit(first,second,1)) trainedCount++
        }
        assertTrue(noviceCount in 900..1150);assertTrue(trainedCount in 2950..3200)
        assertEquals(base.agents[0].genome,first.genome);assertEquals(base.agents[1].genome,second.genome)
    }
    @Test fun dominanceIncludesZeroWeightBoundary() {
        assertEquals(.65,Locus(.2,.8,.25,.75).express(),1e-12)
        assertEquals(.5,Locus(.2,.8,0.0,0.0).express(),1e-12)
        assertEquals(.2,Locus(.2,.8,1.0,0.0).express(),1e-12)
    }
    @Test fun optionalBiologyDoesNotChangeLegacyCanonicalState() {
        val world=WorldActor.genesis()
        assertFalse(Canonical.state(world).contains("biology"))
        assertFalse(Canonical.state(world.copy(minds=mapOf(world.agents[0].id to MindState.initial(world,world.agents[0])))).contains("symbioticModules"))
    }
}
